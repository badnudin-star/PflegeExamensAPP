#!/usr/bin/env python3
"""Validate an internal build run and derive a public release tag from reviewed source."""
import json
from pathlib import Path
import re
import sys


def check(run, expected_sha, gradle):
    if (not re.fullmatch(r'[0-9a-f]{40}', expected_sha) or
        run.get('path') != '.github/workflows/internal-release.yml' or
        run.get('head_branch') != 'main' or run.get('head_sha') != expected_sha or
        run.get('event') != 'workflow_dispatch' or run.get('status') != 'completed' or run.get('conclusion') != 'success'):
        raise ValueError('internal run mismatch')
    codes = re.findall(r'^\s*versionCode\s*=\s*([1-9][0-9]{0,8})\s*$', gradle, re.M)
    names = re.findall(r'^\s*versionName\s*=\s*"([0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5})"\s*$', gradle, re.M)
    if len(codes) != 1 or len(names) != 1: raise ValueError('invalid app version')
    return int(codes[0]), names[0]


def main():
    try:
        runfile, expected_sha, gradlefile, output = sys.argv[1:]
        code, name = check(json.loads(Path(runfile).read_text()), expected_sha, Path(gradlefile).read_text())
        with Path(output).open('a', encoding='utf-8') as handle:
            handle.write(f'code={code}\nname={name}\ntag=v{name}\nasset=PflegeExamen-vc{code}.apk\n')
        print('Bestätigter interner Build, Quellstand und Versionsformat geprüft.')
    except Exception:
        print('::error::Interner Build oder Version stimmt nicht mit dem aktuellen main-Stand überein.')
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
