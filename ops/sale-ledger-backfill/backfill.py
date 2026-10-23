#!/usr/bin/env python3
"""Manual PAR-235 backfill. No connection defaults; dry-run is the default."""
import argparse
from collections import Counter, defaultdict
from bisect import bisect_left, bisect_right
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import uuid

VERSION = 1


class TransferIndex:
    def __init__(self, transfers):
        self.transfers = transfers
        self.by_route = defaultdict(list)
        self.by_id = {t['id']: t for t in transfers}
        for transfer in transfers:
            self.by_route[(transfer['from'], transfer['to'], transfer['amount'])].append(transfer)
        for rows in self.by_route.values():
            rows.sort(key=lambda t: t['time'])

    def nearby(self, sender, receiver, amount, at, tolerance):
        rows = self.by_route.get((sender, receiver, amount), [])
        left = bisect_left(rows, at - tolerance, key=lambda t: t['time'])
        right = bisect_right(rows, at + tolerance, key=lambda t: t['time'])
        return rows[left:right]


def candidates(sale, accounts, index, system_id, tolerance):
    """Return all plausible (canonical txn, participating txns) and a skip reason."""
    if sale['total_price'] <= 0:
        return [], 'non_positive_price'
    customer = accounts.get(sale['customer_uuid_bin'], [])
    shop = sale['shop_account_id']
    if shop is None and not sale['is_admin_shop']:
        owners = accounts.get(sale['shop_owner_uuid_bin'], [])
        shop = owners[0] if len(owners) == 1 else None
    if sale['is_admin_shop'] and shop is None:
        shop = system_id
    if len(customer) != 1 or shop is None or shop == customer[0]:
        return [], 'unresolved_identity'
    buyer, seller = (customer[0], shop) if sale['direction'] == 'BUY' else (shop, customer[0])
    def nearby(sender, receiver):
        return index.nearby(sender, receiver, sale['total_price'], sale['occurred_at'], tolerance)
    result = [(t['id'], (t['id'],)) for t in nearby(buyer, seller)]
    if len(result) > 1000:
        raise RuntimeError('Candidate fanout exceeds 1000; narrow matching window or inspect manually')
    if buyer == system_id or seller == system_id:
        return result, 'no_candidate'
    first = nearby(buyer, system_id)
    second = nearby(system_id, seller)
    if len(result) + len(first) * len(second) > 1000:
        raise RuntimeError('Candidate fanout exceeds 1000; narrow matching window or inspect manually')
    for debit in first:
        for credit in second:
            if legacy_pair(debit, credit):
                result.append((credit['id'], (debit['id'], credit['id'])))
    return result, 'no_candidate'


def legacy_pair(debit, credit):
    # These are the historical bridge message families, not arbitrary fuzzy text.
    message = debit['message']
    if message.startswith('Vault withdraw (') and message.endswith(')'):
        expected = 'Vault deposit (' + message[len('Vault withdraw ('):]
    elif message == 'ChestShop withdrawal':
        expected = 'ChestShop deposit'
    else:
        return False
    return (credit['message'] == expected and debit['id'] < credit['id'] and
            timedelta(0) <= credit['time'] - debit['time'] <= timedelta(seconds=1))


def match(sales, accounts, transfers, system_id, tolerance, claimed):
    index = TransferIndex(transfers)
    owners = defaultdict(list)
    for account in accounts:
        if account['account_type'] == 'PERSONAL':
            owners[account['owner_uuid_bin']].append(account['account_id'])
    choices = {}
    reasons = {}
    users = defaultdict(set)
    candidate_count = 0
    for sale in sales:
        options, reasons[sale['sale_id']] = candidates(sale, owners, index, system_id, tolerance)
        candidate_count += len(options)
        if candidate_count > 10000:
            raise RuntimeError('Batch candidate count exceeds 10000; reduce --batch-seconds')
        choices[sale['sale_id']] = options
        for _, legs in options:
            for leg in legs:
                users[leg].add(sale['sale_id'])
    reports = []
    for sale in sales:
        sid = sale['sale_id']
        options = choices[sid]
        reason = reasons[sid]
        if len(options) > 1:
            reason = 'ambiguous_candidates'
        elif len(options) == 1:
            _, legs = options[0]
            if any(users[leg] != {sid} for leg in legs):
                reason = 'competing_sale'
            elif any(claimed.get(leg, set()) - {sid} for leg in legs):
                reason = 'already_claimed'
            elif len(legs) == 2 and not unique_pair(legs, transfers):
                reason = 'ambiguous_legacy_pair'
            else:
                reason = 'match'
        evidence = {str(leg): dict(txn_id=leg, sender=t['from'], receiver=t['to'],
                                   amount=str(t['amount']), settlement_time=t['time'].isoformat(),
                                   message=t['message'])
                    for _, legs in options for leg in legs for t in [index.by_id[leg]]}
        reports.append(dict(sale_id=sid, occurred_at=sale['occurred_at'].isoformat(),
                            direction=sale['direction'], customer_uuid_hex=sale['customer_uuid_bin'].hex(),
                            shop_account_id=sale['shop_account_id'],
                            shop_owner_uuid_hex=(sale['shop_owner_uuid_bin'].hex()
                                                 if sale['shop_owner_uuid_bin'] else None),
                            is_admin_shop=bool(sale['is_admin_shop']), transactions=evidence,
                            amount=str(sale['total_price']), existing_txn_id=sale['txn_id'],
                            reason=reason, candidates=[dict(txn_id=c, legs=list(l)) for c, l in options]))
    return reports


def unique_pair(legs, transfers):
    by_id = {t['id']: t for t in transfers}
    debit, credit = (by_id[leg] for leg in legs)
    # A different payer/payee without a recorded sale still makes a bridge leg ambiguous.
    return (sum(t['from'] == credit['from'] and t['amount'] == debit['amount'] and
                legacy_pair(debit, t) for t in transfers) == 1 and
            sum(t['to'] == debit['to'] and t['amount'] == credit['amount'] and
                legacy_pair(t, credit) for t in transfers) == 1)


def bounded(cursor, sql, params, limit):
    cursor.execute(sql + ' LIMIT %s', (*params, limit + 1))
    rows = cursor.fetchall()
    if len(rows) > limit:
        raise RuntimeError('Window exceeds row cap; reduce --batch-seconds (no batch changes committed)')
    return rows


def load(cursor, start, end, tolerance, cap, apply):
    halo = tolerance * 2
    sql = '''SELECT sale_id, txn_id, occurred_at, direction, customer_uuid_bin,
                    shop_account_id, shop_owner_uuid_bin, is_admin_shop, total_price
             FROM chestshop_sale WHERE occurred_at >= %s AND occurred_at < %s
             ORDER BY occurred_at, sale_id'''
    # Lock the historical sale interval and its halo in apply mode. The caller must
    # quiesce historical imports/ledger edits; online forward traffic is outside it.
    cursor.execute(sql + ' LIMIT %s' + (' FOR UPDATE' if apply else ''),
                   (start - halo, end + halo, cap + 1))
    sales = cursor.fetchall()
    if len(sales) > cap:
        raise RuntimeError('Sale window exceeds row cap; reduce --batch-seconds')
    postings = bounded(cursor, '''SELECT t.txn_id, t.settlement_time, t.message,
                    p.account_id, p.amount FROM ledger_txns t
                    JOIN ledger_postings p ON p.txn_id=t.txn_id
                    WHERE t.plugin_system COLLATE utf8mb4_bin = 'ChestShop'
                    AND t.settlement_time >= %s AND t.settlement_time < %s
                    ORDER BY t.settlement_time, t.txn_id, p.posting_id''',
                       (start - tolerance * 3, end + tolerance * 3), cap)
    ids = {s['shop_account_id'] for s in sales if s['shop_account_id'] is not None}
    owners = {s['customer_uuid_bin'] for s in sales} | {s['shop_owner_uuid_bin'] for s in sales
                                                              if s['shop_owner_uuid_bin'] is not None}
    # Bounded by the sale cap and posting cap; never load the entire accounts table.
    ids.update(p['account_id'] for p in postings)
    clauses = ["(account_type='SYSTEM' AND display_name COLLATE utf8mb4_bin='ChestShop')"]
    params = []
    for column, values in [('account_id', ids), ('owner_uuid_bin', owners)]:
        if values:
            clauses.append(column + ' IN (' + ','.join(['%s'] * len(values)) + ')')
            params.extend(values)
    accounts = bounded(cursor, 'SELECT account_id, account_type, owner_uuid_bin FROM accounts WHERE '
                       + ' OR '.join(clauses), params, cap)
    cursor.execute("SELECT account_id FROM accounts WHERE account_type='SYSTEM' "
                   "AND display_name COLLATE utf8mb4_bin='ChestShop' LIMIT 2")
    systems = cursor.fetchall()
    if len(systems) != 1:
        raise RuntimeError('Expected exactly one ChestShop SYSTEM account')
    grouped = defaultdict(list)
    for p in postings:
        grouped[p['txn_id']].append(p)
    transfers = []
    for tid, rows in grouped.items():
        if len(rows) != 2:
            continue
        debit = [p for p in rows if p['amount'] < 0]
        credit = [p for p in rows if p['amount'] > 0]
        if len(debit) != 1 or len(credit) != 1 or debit[0]['amount'] != -credit[0]['amount']:
            continue
        transfers.append(dict(id=tid, time=rows[0]['settlement_time'], message=rows[0]['message'],
                              amount=credit[0]['amount'], **{'from': debit[0]['account_id'],
                                                           'to': credit[0]['account_id']}))
    claimed = defaultdict(set)
    # Include claims outside the time range as well as linked sales inside the halo.
    txn_ids = list(grouped)
    claim_count = 0
    for offset in range(0, len(txn_ids), 500):
        chunk = txn_ids[offset:offset + 500]
        rows = bounded(cursor, 'SELECT sale_id, txn_id FROM chestshop_sale WHERE txn_id IN ('
                       + ','.join(['%s'] * len(chunk)) + ')', chunk, cap - claim_count)
        claim_count += len(rows)
        for row in rows:
            claimed[row['txn_id']].add(row['sale_id'])
    return sales, accounts, transfers, systems[0]['account_id'], claimed


def write_report(stream, event):
    stream.write(json.dumps(event, sort_keys=True) + '\n')
    stream.flush()
    os.fsync(stream.fileno())


def _run(connection, args, stream):
    run_id = str(uuid.uuid4())
    counts = Counter()
    tolerance = timedelta(seconds=args.window_seconds)
    write_report(stream, dict(event='run', version=VERSION, run_id=run_id,
                             database=args.database, host=args.host, port=args.port,
                             start=args.start.isoformat(), end=args.end.isoformat(), apply=args.apply,
                             window_seconds=args.window_seconds, batch_seconds=args.batch_seconds,
                             max_rows=args.max_rows))
    with connection.cursor() as cursor:
        cursor.execute("SET time_zone = '+00:00'")
        cursor.execute('SET SESSION innodb_lock_wait_timeout = 5')
        if args.apply:
            cursor.execute("SELECT GET_LOCK('par235-sale-ledger-backfill', 0) AS acquired")
            if cursor.fetchone()['acquired'] != 1:
                raise RuntimeError('Another PAR-235 apply owns the database lock')
        start = args.start
        while start < args.end:
            end = min(start + timedelta(seconds=args.batch_seconds), args.end)
            batch_id = start.isoformat()
            try:
                cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
                cursor.execute('START TRANSACTION' if args.apply else 'START TRANSACTION READ ONLY')
                sales, accounts, transfers, system, claimed = load(cursor, start, end, tolerance,
                                                                  args.max_rows, args.apply)
                reports = match(sales, accounts, transfers, system, tolerance, claimed)
                for report in reports:
                    if not start <= datetime.fromisoformat(report['occurred_at']) < end:
                        continue
                    if report['existing_txn_id'] is not None:
                        counts['already_linked'] += 1
                        continue
                    counts[report['reason']] += 1
                    report.update(event='sale', run_id=run_id, batch=batch_id)
                    # Durable intent before the DB commit. Only a later batch_committed
                    # event confirms writes; a crash in between is reconciled by rerun.
                    write_report(stream, report)
                    if args.apply and report['reason'] == 'match':
                        cursor.execute('UPDATE chestshop_sale SET txn_id=%s WHERE sale_id=%s AND txn_id IS NULL',
                                       (report['candidates'][0]['txn_id'], report['sale_id']))
                        if cursor.rowcount != 1:
                            raise RuntimeError('Sale changed during batch; rolling back')
                if args.apply:
                    write_report(stream, dict(event='batch_prepared', run_id=run_id, batch=batch_id))
                    connection.commit()
                else:
                    connection.rollback()
                write_report(stream, dict(event='batch_committed' if args.apply else 'batch_dry_run',
                                         run_id=run_id, batch=batch_id, resume_from=end.isoformat()))
            except BaseException:
                connection.rollback()
                write_report(stream, dict(event='batch_failed', run_id=run_id, batch=batch_id))
                raise
            start = end
    eligible = sum(v for k, v in counts.items() if k != 'already_linked')
    summary = dict(event='summary', run_id=run_id, counts=dict(counts), eligible=eligible,
                   matched_percent=round(100 * counts['match'] / eligible, 2) if eligible else 0)
    write_report(stream, summary)
    return summary


def run(connection, args, stream):
    try:
        return _run(connection, args, stream)
    finally:
        if args.apply:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK('par235-sale-ledger-backfill')")
            except Exception:
                # A closed/lost connection releases its own advisory locks server-side.
                pass


def utc(value):
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise argparse.ArgumentTypeError('Use an explicit UTC timestamp, e.g. 2026-06-01T00:00:00Z')
    return parsed.astimezone(timezone.utc).replace(tzinfo=None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--unix-socket', help='Optional task-local MariaDB socket (host/port remain audit labels)')
    parser.add_argument('--database', required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--start', type=utc, required=True)
    parser.add_argument('--end', type=utc, required=True)
    parser.add_argument('--batch-seconds', type=int, default=60)
    parser.add_argument('--window-seconds', type=int, default=2)
    parser.add_argument('--max-rows', type=int, default=10000)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not (args.start < args.end and 1 <= args.batch_seconds <= 3600
            and 1 <= args.window_seconds <= 5 and 1 <= args.max_rows <= 100000):
        parser.error('Require start < end, batch 1..3600s, window 1..5s, max rows 1..100000')
    import pymysql
    # Credentials are never accepted in a command argument or written to the report.
    connection = pymysql.connect(host=args.host, port=args.port, user=args.user,
                                 password=os.environ.get('PAR235_DB_PASSWORD', ''), database=args.database,
                                 unix_socket=args.unix_socket, charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor,
                                 autocommit=True, connect_timeout=10, read_timeout=60, write_timeout=60)
    try:
        # Refuse to overwrite evidence from a previous attempt.
        with os.fdopen(os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
                       'w', encoding='utf-8') as stream:
            print(json.dumps(run(connection, args, stream), sort_keys=True))
    finally:
        connection.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'PAR-235 stopped: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
