#!/usr/bin/env python3
"""Check the public endpoint and feature flag in generated release BuildConfig."""
from pathlib import Path
import re
import sys

URL = 'https://pflege-examen-ranking.badnudin.workers.dev/leaderboard'

def verify(path):
    text = Path(path).read_text(encoding='utf-8')
    checks = [r'public\s+static\s+final\s+boolean\s+ENABLE_RANKED_PARTICIPATION\s*=\s*true\s*;',
              r'public\s+static\s+final\s+String\s+RANKING_URL\s*=\s*"' + re.escape(URL) + r'"\s*;']
    if any(len(re.findall(pattern, text)) != 1 for pattern in checks):
        raise ValueError('Ranking-Konfiguration stimmt nicht überein')

def main():
    try:
        if len(sys.argv) != 2: raise ValueError('Eingabe fehlt')
        verify(sys.argv[1])
    except (ValueError, OSError):
        print('::error::Ranking-Buildkonfiguration konnte nicht bestätigt werden.')
        return 1
    print('Ranking-URL und Teilnahme im Release-Build bestätigt.')
    return 0

if __name__ == '__main__': sys.exit(main())
