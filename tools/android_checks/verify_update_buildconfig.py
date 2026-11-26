#!/usr/bin/env python3
"""Ensure the signed distribution has its automatic app-update switch enabled."""
from pathlib import Path
import re
import sys


def verify(path):
    text = Path(path).read_text(encoding='utf-8')
    pattern = r'public\s+static\s+final\s+boolean\s+ENABLE_APP_UPDATES\s*=\s*true\s*;'
    if len(re.findall(pattern, text)) != 1: raise ValueError('app updates disabled')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2: raise ValueError('missing buildconfig')
        verify(sys.argv[1])
        print('App-Updatefunktion im signierten Build aktiviert.')
    except (OSError, ValueError):
        print('::error::App-Updatefunktion im Build konnte nicht bestätigt werden.')
        sys.exit(1)
