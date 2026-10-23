"""Disposable MariaDB contract tests; never point these at a shared server."""
from datetime import datetime, timedelta
from decimal import Decimal
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import uuid

import backfill

NOW = datetime(2026, 1, 1)
CUSTOMER = bytes.fromhex('11' * 16)
OWNER = bytes.fromhex('22' * 16)


def account(aid, owner, kind='PERSONAL'):
    return dict(account_id=aid, account_type=kind, owner_uuid_bin=owner)


def sale(sid=1, **changes):
    row = dict(sale_id=sid, txn_id=None, occurred_at=NOW, direction='BUY',
               customer_uuid_bin=CUSTOMER, shop_account_id=2, shop_owner_uuid_bin=OWNER,
               is_admin_shop=0, total_price=Decimal('12.34'))
    return row | changes


def transfer(tid, sender=1, receiver=2, seconds=0, amount='12.34', message='trade'):
    return dict(id=tid, time=NOW + timedelta(seconds=seconds), amount=Decimal(amount),
                message=message, **{'from': sender, 'to': receiver})


class MatchingTests(unittest.TestCase):
    def report(self, sales=None, transfers=None, claimed=None):
        return backfill.match(sales or [sale()], [account(1, CUSTOMER), account(2, OWNER)],
                              transfers or [transfer(100)], 9, timedelta(seconds=2), claimed or {})

    def test_exact_money_direction_identity_and_time(self):
        self.assertEqual(self.report()[0]['candidates'], [{'txn_id': 100, 'legs': [100]}])
        for changes in [dict(amount='12.33'), dict(sender=3), dict(receiver=3),
                        dict(sender=2, receiver=1), dict(seconds=2.001)]:
            with self.subTest(changes=changes):
                self.assertEqual(self.report(transfers=[transfer(100, **changes)])[0]['reason'], 'no_candidate')
        for seconds in [-2, 2]:
            self.assertEqual(self.report(transfers=[transfer(100, seconds=seconds)])[0]['reason'], 'match')

    def test_personal_owner_fallback_and_sell_direction(self):
        self.assertEqual(self.report(sales=[sale(shop_account_id=None)])[0]['reason'], 'match')
        result = self.report(sales=[sale(direction='SELL')], transfers=[transfer(101, 2, 1)])
        self.assertEqual(result[0]['reason'], 'match')
        self.assertEqual(self.report(sales=[sale(customer_uuid_bin=OWNER)])[0]['reason'], 'unresolved_identity')

    def test_legacy_seller_credit_for_buy_and_sell(self):
        for direction, buyer, seller in [('BUY', 1, 2), ('SELL', 2, 1)]:
            with self.subTest(direction=direction):
                txns = [transfer(100, buyer, 9, message='Vault withdraw (ChestShop)'),
                        transfer(101, 9, seller, seconds=1, message='Vault deposit (ChestShop)')]
                result = self.report(sales=[sale(direction=direction)], transfers=txns)[0]
                self.assertEqual(result['reason'], 'match')
                self.assertEqual(result['candidates'], [{'txn_id': 101, 'legs': [100, 101]}])

    def test_legacy_requires_order_time_and_message(self):
        debit = transfer(100, 1, 9, message='Vault withdraw (ChestShop)')
        for credit in [transfer(101, 9, 2, seconds=1.001, message='Vault deposit (ChestShop)'),
                       transfer(99, 9, 2, message='Vault deposit (ChestShop)'),
                       transfer(101, 9, 2, seconds=-1, message='Vault deposit (ChestShop)'),
                       transfer(101, 9, 2, message='Vault deposit (Other)')]:
            self.assertEqual(self.report(transfers=[debit, credit])[0]['reason'], 'no_candidate')

    def test_ambiguous_transactions_and_sales_and_claims(self):
        self.assertEqual(self.report(transfers=[transfer(100), transfer(101)])[0]['reason'], 'ambiguous_candidates')
        results = self.report(sales=[sale(), sale(2, occurred_at=NOW + timedelta(seconds=3))],
                              transfers=[transfer(100, seconds=1)])
        self.assertEqual([r['reason'] for r in results], ['competing_sale', 'competing_sale'])
        self.assertEqual(self.report(claimed={100: {999}})[0]['reason'], 'already_claimed')
        result = self.report(sales=[sale(), sale(2, txn_id=100)])
        self.assertEqual(result[0]['reason'], 'competing_sale')

    def test_unrecorded_other_customer_still_makes_bridge_ambiguous(self):
        txns = [transfer(100, 1, 9, message='Vault withdraw (ChestShop)'),
                transfer(101, 3, 9, message='Vault withdraw (ChestShop)'),
                transfer(102, 9, 2, message='Vault deposit (ChestShop)')]
        self.assertEqual(self.report(transfers=txns)[0]['reason'], 'ambiguous_legacy_pair')

    def test_admin_single_leg_free_and_missing_identity(self):
        result = self.report(sales=[sale(shop_account_id=None, is_admin_shop=1)],
                             transfers=[transfer(100, 1, 9)])
        self.assertEqual(result[0]['reason'], 'match')
        self.assertEqual(self.report(sales=[sale(total_price=Decimal('0'))])[0]['reason'], 'non_positive_price')
        self.assertEqual(self.report(sales=[sale(shop_account_id=None, shop_owner_uuid_bin=None)])[0]['reason'],
                         'unresolved_identity')


@unittest.skipUnless(os.environ.get('PAR235_TEST_SOCKET'), 'Set PAR235_TEST_SOCKET to a disposable MariaDB socket')
class DatabaseTests(unittest.TestCase):
    def setUp(self):
        import pymysql
        self.connection = pymysql.connect(unix_socket=os.environ['PAR235_TEST_SOCKET'], user='root',
                                          charset='utf8mb4', autocommit=True,
                                          cursorclass=pymysql.cursors.DictCursor)
        self.database = 'par235_test_' + uuid.uuid4().hex
        with self.connection.cursor() as cursor:
            cursor.execute('CREATE DATABASE ' + self.database)
            self.addCleanup(self.cleanup_database)
            cursor.execute('USE ' + self.database)
            # Use the real base schema, removing only the CLI-incompatible UUID routines.
            import re
            root = Path(__file__).resolve().parents[2]
            initial = (root / 'economy-flyway/src/main/resources/db/migration/V1__initial.sql').read_text()
            routines = re.findall(r'CREATE (?:FUNCTION|TRIGGER) .*?END;', initial, flags=re.S)
            initial = re.sub(r'CREATE (?:FUNCTION|TRIGGER) .*?END;', '', initial, flags=re.S)
            sales = (root / 'economy-flyway/src/main/resources/db/migration/V6__chestshop_sales.sql').read_text()
            # Strip comments before splitting (some comments contain semicolons).
            for text in [initial, sales]:
                text = re.sub(r'--[^\n]*', '', text)
                for statement in text.split(';'):
                    if statement.strip():
                        cursor.execute(statement)
            for routine in routines:
                cursor.execute(routine)
            cursor.execute('ALTER TABLE chestshop_sale ADD FOREIGN KEY (txn_id) REFERENCES ledger_txns(txn_id)')
            for aid, owner, kind, name in [(1, CUSTOMER, 'PERSONAL', 'Customer'), (2, OWNER, 'PERSONAL', 'Shop'),
                                           (9, bytes(16), 'SYSTEM', 'ChestShop')]:
                cursor.execute('INSERT INTO accounts(account_id,owner_uuid_bin,account_type,display_name) VALUES(%s,%s,%s,%s)',
                               (aid, owner, kind, name))
        scratch_root = Path(os.environ.get('PAR235_TEST_TMP', '.context/par235'))
        scratch_root.mkdir(parents=True, exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(dir=scratch_root)
        self.addCleanup(self.scratch.cleanup)

    def cleanup_database(self):
        self.connection.rollback()
        with self.connection.cursor() as cursor:
            cursor.execute('DROP DATABASE ' + self.database)
        self.connection.close()

    def seed(self, sid=1, tid=100, seconds=0, amount='12.34', plugin='ChestShop', extra=False):
        with self.connection.cursor() as cursor:
            cursor.execute('INSERT INTO chestshop_sale(sale_id,occurred_at,direction,customer_uuid_bin,shop_account_id,'
                           'material,item_key,item_name,quantity,unit_price,total_price) '
                           "VALUES(%s,%s,'BUY',%s,2,'STONE','STONE','Stone',1,12.34,12.34)",
                           (sid, NOW + timedelta(seconds=seconds), CUSTOMER))
            if tid is not None:
                cursor.execute('INSERT INTO ledger_txns(txn_id,settlement_time,message,initiator_uuid_bin,plugin_system) '
                               "VALUES(%s,%s,'trade',%s,%s)", (tid, NOW + timedelta(seconds=seconds), CUSTOMER, plugin))
                cursor.executemany('INSERT INTO ledger_postings(txn_id,account_id,amount) VALUES(%s,%s,%s)',
                                   [(tid, 1, -Decimal(amount)), (tid, 2, Decimal(amount))])
                if extra:
                    cursor.execute('INSERT INTO ledger_postings(txn_id,account_id,amount) VALUES(%s,9,0)', (tid,))

    def run_backfill(self, apply=False, **changes):
        args = SimpleNamespace(database=self.database, host='disposable-socket', port=0,
                               start=NOW, end=NOW + timedelta(seconds=120), apply=apply,
                               window_seconds=2, batch_seconds=60, max_rows=1000)
        for key, value in changes.items():
            setattr(args, key, value)
        self.report_path = Path(self.scratch.name) / (uuid.uuid4().hex + '.jsonl')
        with self.report_path.open('w') as stream:
            return backfill.run(self.connection, args, stream)

    def links(self):
        with self.connection.cursor() as cursor:
            cursor.execute('SELECT sale_id,txn_id FROM chestshop_sale ORDER BY sale_id')
            return [(row['sale_id'], row['txn_id']) for row in cursor.fetchall()]

    def test_read_only_dry_run_apply_rerun_and_exact_decimal(self):
        self.seed()
        self.seed(2, 101, seconds=10, amount='12.33')
        dry = self.run_backfill()
        self.assertEqual(dry['matched_percent'], 50)
        self.assertEqual(self.links(), [(1, None), (2, None)])
        applied = self.run_backfill(True)
        self.assertEqual(applied['counts']['match'], 1)
        self.assertEqual(self.links(), [(1, 100), (2, None)])
        rerun = self.run_backfill(True)
        self.assertEqual(rerun['counts'], {'already_linked': 1, 'no_candidate': 1})
        self.assertEqual(self.links(), [(1, 100), (2, None)])

    def test_plugin_exact_case_and_multileg_rejected(self):
        self.seed(plugin='Other')
        self.seed(2, 101, 10, plugin='chestshop')
        self.seed(3, 102, 20, extra=True)
        result = self.run_backfill(True)
        self.assertEqual(result['counts'], {'no_candidate': 3})
        self.assertEqual(self.links(), [(1, None), (2, None), (3, None)])

    def test_cross_batch_and_outside_range_competition(self):
        self.seed(seconds=59)
        self.seed(2, None, 61)
        result = self.run_backfill(True, end=NOW + timedelta(seconds=60))
        self.assertEqual(result['counts'], {'competing_sale': 1})
        self.assertEqual(self.links(), [(1, None), (2, None)])

    def test_claim_outside_halo_blocks_reuse(self):
        self.seed()
        self.seed(2, None, 500)
        with self.connection.cursor() as cursor:
            cursor.execute('UPDATE chestshop_sale SET txn_id=100 WHERE sale_id=2')
        self.assertEqual(self.run_backfill(True)['counts'], {'already_claimed': 1})
        self.assertEqual(self.links(), [(1, None), (2, 100)])

    def test_failure_rolls_back_current_batch_and_resumes(self):
        self.seed()
        self.seed(2, 101, 70)
        self.seed(3, 102, 80)
        with self.connection.cursor() as cursor:
            cursor.execute("CREATE TRIGGER fail_par235 BEFORE UPDATE ON chestshop_sale FOR EACH ROW "
                           "BEGIN IF NEW.sale_id=3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected failure'; END IF; END")
        with self.assertRaisesRegex(Exception, 'injected failure'):
            self.run_backfill(True)
        self.assertEqual(self.links(), [(1, 100), (2, None), (3, None)])
        events = [json.loads(line) for line in self.report_path.read_text().splitlines()]
        self.assertEqual([e['event'] for e in events if e['event'].startswith('batch_')],
                         ['batch_prepared', 'batch_committed', 'batch_failed'])
        with self.connection.cursor() as cursor:
            cursor.execute('DROP TRIGGER fail_par235')
        self.run_backfill(True, start=NOW + timedelta(seconds=60))
        self.assertEqual(self.links(), [(1, 100), (2, 101), (3, 102)])

    def test_cli_dry_run_private_report_and_no_overwrite(self):
        self.seed()
        report = Path(self.scratch.name) / 'cli.jsonl'
        argv = [sys.executable, str(Path(backfill.__file__)), '--host', 'task-owned-socket', '--port', '0',
                '--unix-socket', os.environ['PAR235_TEST_SOCKET'], '--database', self.database,
                '--user', 'root', '--start', '2026-01-01T00:00:00Z', '--end', '2026-01-01T00:01:00Z',
                '--report', str(report)]
        result = subprocess.run(argv, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['counts'], {'match': 1})
        self.assertEqual(self.links(), [(1, None)])
        self.assertEqual(report.stat().st_mode & 0o777, 0o600)
        original = report.read_text()
        overwrite = subprocess.run(argv + ['--apply'], capture_output=True, text=True)
        self.assertNotEqual(overwrite.returncode, 0)
        self.assertIn('FileExistsError', overwrite.stderr)
        self.assertEqual(report.read_text(), original)
        self.assertEqual(self.links(), [(1, None)])

    def test_legacy_canonical_link_and_ledger_unchanged(self):
        self.seed(tid=None)
        with self.connection.cursor() as cursor:
            for tid, sender, receiver, message in [(100, 1, 9, 'Vault withdraw (ChestShop)'),
                                                    (101, 9, 2, 'Vault deposit (ChestShop)')]:
                cursor.execute('INSERT INTO ledger_txns(txn_id,settlement_time,message,initiator_uuid_bin,plugin_system) '
                               "VALUES(%s,%s,%s,%s,'ChestShop')", (tid, NOW, message, CUSTOMER))
                cursor.executemany('INSERT INTO ledger_postings(txn_id,account_id,amount) VALUES(%s,%s,%s)',
                                   [(tid, sender, Decimal('-12.34')), (tid, receiver, Decimal('12.34'))])
            cursor.execute('SELECT * FROM ledger_postings ORDER BY posting_id')
            postings = cursor.fetchall()
            cursor.execute('SELECT * FROM account_balances_mat ORDER BY account_id')
            balances = cursor.fetchall()
        self.run_backfill(True)
        self.assertEqual(self.links(), [(1, 101)])
        events = [json.loads(line) for line in self.report_path.read_text().splitlines()]
        evidence = next(e for e in events if e['event'] == 'sale')
        self.assertEqual(evidence['candidates'], [{'txn_id': 101, 'legs': [100, 101]}])
        self.assertEqual(evidence['transactions']['101']['amount'], '12.34')
        self.assertEqual(evidence['transactions']['101']['receiver'], 2)
        with self.connection.cursor() as cursor:
            cursor.execute('SELECT * FROM ledger_postings ORDER BY posting_id')
            self.assertEqual(cursor.fetchall(), postings)
            cursor.execute('SELECT * FROM account_balances_mat ORDER BY account_id')
            self.assertEqual(cursor.fetchall(), balances)

    def test_apply_serialization_and_lock_release(self):
        import pymysql
        self.seed()
        blocker = pymysql.connect(unix_socket=os.environ['PAR235_TEST_SOCKET'], user='root', autocommit=True)
        try:
            with blocker.cursor() as cursor:
                cursor.execute("SELECT GET_LOCK('par235-sale-ledger-backfill',0)")
            with self.assertRaisesRegex(RuntimeError, 'Another PAR-235'):
                self.run_backfill(True)
            self.assertEqual(self.links(), [(1, None)])
        finally:
            blocker.close()
        self.run_backfill(True)
        with self.connection.cursor() as cursor:
            cursor.execute("SELECT IS_FREE_LOCK('par235-sale-ledger-backfill') AS free")
            self.assertEqual(cursor.fetchone()['free'], 1)
        self.assertEqual(self.links(), [(1, 100)])

    def test_cap_fails_without_writes(self):
        self.seed()
        with self.assertRaisesRegex(RuntimeError, 'cap'):
            self.run_backfill(True, max_rows=1)
        self.assertEqual(self.links(), [(1, None)])

    def test_read_only_database_user_can_dry_run(self):
        import pymysql
        self.seed()
        user = 'par235_' + uuid.uuid4().hex[:12]
        with self.connection.cursor() as cursor:
            cursor.execute("CREATE USER %s@'localhost'", (user,))
            cursor.execute('GRANT SELECT ON ' + self.database + ".* TO %s@'localhost'", (user,))
        original = self.connection
        self.connection = pymysql.connect(unix_socket=os.environ['PAR235_TEST_SOCKET'], user=user,
                                          database=self.database, charset='utf8mb4', autocommit=True,
                                          cursorclass=pymysql.cursors.DictCursor)
        try:
            self.assertEqual(self.run_backfill()['counts'], {'match': 1})
        finally:
            self.connection.close()
            self.connection = original
            with self.connection.cursor() as cursor:
                cursor.execute("DROP USER %s@'localhost'", (user,))
        self.assertEqual(self.links(), [(1, None)])


if __name__ == '__main__':
    unittest.main()
