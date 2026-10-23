#!/usr/bin/env python3
"""Source index for PAR-499 review. Discovery is NOT proof of behavioral coverage.

Run from any directory; --check fails if production entry points changed without
refreshing the review index. Java annotations are indexed syntactically, so both
registered and potentially unregistered handlers are intentionally included.
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(__file__).with_name('entry-points.json')
MODULES = ('treasury', 'business', 'chestshop', 'treasury-api-plugin', 'treasury-rest-api')


def discover():
    rows = []
    patterns = {
        'configuration': r'@ConfigurationValue\(\s*path\s*=\s*"([^"]+)"',
        'permission': r'@(?:[\w.]+\.)?Permission\(([^\n]*)\)',
        'command': r'@(?:[\w.]+\.)?Route\("([^"]*)"\)',
        'http': r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^\n]*)\))?',
        'event': r'@EventHandler(?:\([^\n]*\))?',
        'scheduled': r'@Scheduled\([^\n]*\)|\b(?:runTaskTimer(?:Asynchronously)?|scheduleSyncRepeatingTask)\s*\(',
    }
    for module in MODULES:
        for source in sorted((ROOT / module / 'src/main/java').rglob('*.java')):
            text = source.read_text()
            # Preserve line numbers while excluding comments from annotation discovery.
            code = re.sub(r'/\*.*?\*/|//[^\n]*',
                          lambda m: '\n' * m.group().count('\n'), text, flags=re.S)
            commands = re.search(r'@Command\((.*?)\)', code, flags=re.S)
            roots = re.findall(r'"([^"]+)"', commands[1]) if commands else []
            prefix = re.search(r'@RequestMapping\("([^"]*)"\)', code)
            for kind, pattern in patterns.items():
                for match in re.finditer(pattern, code):
                    row = {'kind': kind, 'source': str(source.relative_to(ROOT)),
                           'line': code[:match.start()].count('\n') + 1}
                    if kind in ('configuration', 'permission'):
                        row['value'] = match[1]
                    elif kind == 'command':
                        row.update(roots=roots, route=match[1])
                    elif kind == 'http':
                        suffix = re.findall(r'"([^"]*)"', match[2] or '')
                        row.update(method=match[1].upper(),
                                   path=(prefix[1] if prefix else '') + (suffix[0] if suffix else ''))
                    else:
                        row['trigger'] = match[0]
                        method = re.search(r'\b(?:public|private|protected)\s+(?:static\s+)?\w+\s+(\w+)\s*\(', code[match.end():])
                        if kind == 'event' and method:
                            row['handler'] = method[1]
                    rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    entries = discover()
    payload = json.dumps(dict(review='Unreviewed source index; see matrix.md for behavioral evidence',
                              entries=entries), indent=2) + '\n'
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != payload:
            parser.error('entry points changed; run inventory.py and review matrix.md')
    else:
        OUTPUT.write_text(payload)
    print(f'{len(entries)} entry points indexed; behavioral review remains separate')


if __name__ == '__main__':
    main()
