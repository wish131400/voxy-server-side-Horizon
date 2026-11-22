"""Run prepared version cases sequentially; unavailable lanes stay incomplete."""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import acceptance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cases', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    cases = acceptance.read(args.cases)
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for spec in cases:
        target = spec['target']
        if not (acceptance.HERE / 'profiles' / (target + '.json')).is_file():
            raise ValueError('Unknown version target')
        destination = args.output / target
        needed = ('case', 'sessions', 'mcpCommand')
        if not all(spec.get(key) and Path(spec[key]).is_file() for key in needed):
            row = {'target': target, 'status': 'incomplete', 'reason': 'Version fixture or Bridge adapter not prepared'}
        else:
            command = [sys.executable, str(acceptance.HERE / 'acceptance.py'), 'run', target,
                       spec['case'], spec['sessions'], spec['mcpCommand'], str(destination)]
            with (args.output / (target + '.log')).open('w', encoding='utf-8') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            evidence = destination / 'acceptance-result.json'
            row = {'target': target, 'exitCode': result.returncode,
                   'status': acceptance.read(evidence)['status'] if evidence.is_file() else 'incomplete'}
        results.append(row)
        acceptance.write(args.output / 'matrix-result.json', results)
        print(json.dumps(row), flush=True)


if __name__ == '__main__':
    main()
