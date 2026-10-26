#!/usr/bin/env python3
"""Conservative PR test selection; unknown dependencies always run the full suite."""
import argparse
import json
from pathlib import Path
import subprocess

TEST_ROOT = Path('integration-tests/tests/src/test/java')
PACKAGE = 'net.democracycraft.inttests'
# These are consumer groups, not just the module's own tests. REST endpoints are
# also exercised by treasury commands; ChestShop publishes market data consumed
# by both. Lifecycle tests cross runtime boundaries.
MODULE_GROUPS = {
    'treasury-rest-api': {'restapi', 'treasury', 'lifecycle'},
    'treasury-api-plugin': {'restapi', 'treasury', 'lifecycle'},
    'chestshop': {'chestshop', 'restapi', 'treasury', 'lifecycle'},
}
GROUPS = {'business', 'chestshop', 'restapi', 'treasury', 'lifecycle', 'diag', 'support'}


def full(reason):
    return {'mode': 'full', 'filters': [], 'reason': reason}


def select(paths, root):
    """Paths include both sides of renames (git diff --no-renames)."""
    filters = set()
    for name in paths:
        path = Path(name)
        if path.is_relative_to(TEST_ROOT):
            relative = path.relative_to(TEST_ROOT)
            parts = relative.parts
            if (not (root / path).is_file() or path.suffix != '.java'
                    or not path.name.endswith('Test.java')
                    or parts[:3] != ('net', 'democracycraft', 'inttests')):
                return full(f'Shared, deleted or unrecognized test source: {name}')
            if len(parts) == 4:
                filters.add('.'.join(relative.with_suffix('').parts))
            elif len(parts) == 5 and parts[3] in GROUPS - {'support'}:
                filters.add(f'{PACKAGE}.{parts[3]}.*')
            else:
                return full(f'Shared or unrecognized test group: {name}')
        elif (len(path.parts) > 2 and path.parts[0] in MODULE_GROUPS
              and path.parts[1] == 'src'):
            filters.update(f'{PACKAGE}.{group}.*' for group in MODULE_GROUPS[path.parts[0]])
            # Keep every top-level smoke/cross-plugin test without using a '*'
            # package wildcard, which Gradle would also match across subpackages.
            filters.update(f'{PACKAGE}.{p.stem}' for p in
                           (root / TEST_ROOT / 'net/democracycraft/inttests').glob('*Test.java'))
        elif (name.startswith('wiki/') or name.startswith('economy-explorer/docs/')
              or name in {'README.md', 'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md', 'LICENSE.md', 'SECURITY.md'}):
            continue
        else:
            return full(f'Shared dependency or unmapped path: {name}')
    if not filters:
        return full('No safely selectable test groups')
    return {'mode': 'affected', 'filters': sorted(filters), 'reason': 'PR changes and downstream consumers'}


def selection_for_base(base, root):
    if not base:
        return full('Non-PR run: full suite')
    try:
        result = subprocess.run(
            ['git', 'diff', '--name-only', '--no-renames', '-z', f'{base}...HEAD', '--'],
            cwd=root, check=True, capture_output=True,
        )
    except subprocess.CalledProcessError:
        return full('Base diff unavailable: full suite')
    return select(result.stdout.decode().split('\0')[:-1], root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='', help='PR base SHA; omit for the full suite')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    selection = selection_for_base(args.base, Path(__file__).resolve().parents[2])
    print(json.dumps(selection, indent=2))
    if args.github_output:
        with args.github_output.open('a') as output:
            output.write('filters=' + json.dumps(selection['filters']) + '\n')


if __name__ == '__main__':
    main()
