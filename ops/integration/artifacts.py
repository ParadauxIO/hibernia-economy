"""Record the JAR bytes selected for a disposable acceptance stack, without paths/configs."""
import argparse
import hashlib
import json
from pathlib import Path


def inventory(paper, plugins, rest):
    files = {'paper/' + paper.name: paper, 'rest/' + rest.name: rest}
    files.update({'plugins/' + path.name: path for path in sorted(plugins.glob('*.jar'))})
    if not any(name.startswith('plugins/') for name in files):
        raise ValueError('No installed plugin JARs to certify')
    result = {}
    for name, path in files.items():
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        size = path.stat().st_size
        if not size:
            raise ValueError(f'Empty runtime artifact: {name}')
        result[name] = {'sha256': digest, 'bytes': size}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('paper', 'plugins', 'rest', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inventory(args.paper, args.plugins, args.rest), indent=2) + '\n')
