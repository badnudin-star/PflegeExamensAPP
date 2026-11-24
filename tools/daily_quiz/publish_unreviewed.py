#!/usr/bin/env python3
"""Explicitly publish one unreviewed draft, with a visible warning on every question."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re

import quiz_pipeline

WARNING = 'KI-generiert, fachlich ungeprüft: '
MAX_DRAFT_BYTES = 4 * 1024 * 1024


def prepare(raw, expected_hash, confirmed):
    """Fail before network activity; do not imply professional review occurred."""
    if confirmed != 'true':
        raise ValueError('Veröffentlichung ungeprüfter Fragen nicht bestätigt')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected_hash):
        raise ValueError('Ungültige Prüfsumme')
    if len(raw) > MAX_DRAFT_BYTES or hashlib.sha256(raw).hexdigest() != expected_hash.lower():
        raise ValueError('Entwurf zu groß oder Prüfsumme stimmt nicht')
    draft = json.loads(raw)
    quiz_pipeline.validate_draft(draft)
    if any(q['question'].startswith(WARNING) for q in draft['quizzes']):
        raise ValueError('Entwurf bereits markiert')
    result = copy.deepcopy(draft)
    for q in result['quizzes']:
        q['question'] = WARNING + q['question']
    quiz_pipeline.validate_draft(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--draft', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--worker', required=True)
    parser.add_argument('--bundled', type=Path, default=Path('app/src/main/assets/learning_content.json'))
    args = parser.parse_args()
    if args.draft.stat().st_size > MAX_DRAFT_BYTES:
        raise ValueError('Entwurf zu groß')
    prepared = prepare(args.draft.read_bytes(), args.sha256, os.environ.get('UNREVIEWED_CONFIRMED', ''))
    bundled = json.loads(args.bundled.read_text(encoding='utf-8'))
    import requests
    quiz_pipeline.publish(requests, args.worker, os.environ.get('CONTENT_PUBLISH_TOKEN', ''), prepared, bundled)
    print('Ungeprüfte Fragen mit Warnhinweis veröffentlicht; keine fachliche Prüfung behauptet.')


if __name__ == '__main__':
    main()
