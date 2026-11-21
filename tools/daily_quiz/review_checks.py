#!/usr/bin/env python3
"""Non-clinical, read-only review hints for a validated 50-question draft."""
import argparse
from collections import Counter, defaultdict
from difflib import SequenceMatcher
import json
from pathlib import Path
import re

from quiz_pipeline import validate_draft

MAX_PAIRS = 20


def question_key(text):
    return ' '.join(re.findall(r'\w+', text.casefold(), flags=re.UNICODE))[:512]


def analyze(draft):
    """Hints are intentionally non-blocking and cannot assess medical correctness."""
    questions = validate_draft(draft)['quizzes']
    topics = Counter(q['topic'] for q in questions)
    answer_counts = [sum(q['correctIndex'] == i for q in questions) for i in range(4)]
    keys = [question_key(q['question']) for q in questions]
    similar = []
    total_similar = 0
    for i in range(len(questions)):
        for j in range(i + 1, len(questions)):
            if questions[i]['topic'] != questions[j]['topic']:
                continue
            if SequenceMatcher(None, keys[i], keys[j], autojunk=False).ratio() >= 0.90:
                total_similar += 1
                if len(similar) < MAX_PAIRS:
                    similar.append((i + 1, j + 1))
    excerpts = defaultdict(list)
    for i, q in enumerate(questions, 1):
        excerpts[(q['topic'], q['sources'][1])].append(i)
    repeated = [numbers for numbers in excerpts.values() if len(numbers) >= 4]
    return {'question_count': len(questions), 'topic_count': len(topics),
            'answer_counts': answer_counts, 'similar_pairs': similar,
            'total_similar_pairs': total_similar, 'repeated_evidence': repeated}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('draft', type=Path)
    args = parser.parse_args()
    if args.draft.stat().st_size > 4 * 1024 * 1024:
        raise ValueError('Entwurf zu groß')
    report = analyze(json.loads(args.draft.read_text(encoding='utf-8')))
    # Only counts are printed, never questions, excerpts, secrets or model output.
    counts = ', '.join(f'{chr(65 + i)}: {n}' for i, n in enumerate(report['answer_counts']))
    lines = [f"Fragen: {report['question_count']}; Themen: {report['topic_count']}",
             'Antwortpositionen: ' + counts,
             f"Ähnliche Fragepaare im selben Thema: {report['total_similar_pairs']}",
             f"Mindestens viermal verwendete Belege im selben Thema: {len(report['repeated_evidence'])}",
             'Nur automatische Hinweise, keine fachliche Prüfung oder Freigabe.']
    for line in lines:
        print('::notice::' + line)


if __name__ == '__main__':
    main()
