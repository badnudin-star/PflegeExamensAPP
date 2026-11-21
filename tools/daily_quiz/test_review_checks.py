import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import review_checks
import review_sheet
import test_quiz_pipeline

ROOT = Path(__file__).resolve().parent


class ReviewChecksTests(unittest.TestCase):
    def draft(self):
        return test_quiz_pipeline.QuizTests().draft()

    def test_counts_and_nonblocking_flags(self):
        draft = self.draft()
        first, second = draft['quizzes'][:2]
        first['question'] = 'Wie kann man die Situation richtig einschätzen?'
        second['question'] = 'Wie kann man die Situation richtig einschätzen!'
        for q in draft['quizzes']:
            q['correctIndex'] = 0
        report = review_checks.analyze(draft)
        self.assertEqual(report['question_count'], 50)
        self.assertEqual(report['topic_count'], 5)
        self.assertEqual(report['answer_counts'], [50, 0, 0, 0])
        self.assertIn((1, 2), report['similar_pairs'])
        self.assertTrue(any(set(range(1, 11)) == set(group) for group in report['repeated_evidence']))
        html = review_sheet.render(json.dumps(draft, ensure_ascii=False).encode())
        self.assertIn('A: 50 · B: 0 · C: 0 · D: 0', html)
        self.assertIn('href="#frage-1"', html)
        self.assertIn('weder medizinische Richtigkeit', html)
        self.assertEqual(html.count('class="question"'), 50)

    def test_pair_limit_and_different_topics(self):
        draft = self.draft()
        original = copy.deepcopy(draft)
        report = review_checks.analyze(draft)
        self.assertEqual(draft, original)
        self.assertLessEqual(len(report['similar_pairs']), review_checks.MAX_PAIRS)
        self.assertGreaterEqual(report['total_similar_pairs'], len(report['similar_pairs']))
        self.assertTrue(all(draft['quizzes'][a-1]['topic'] == draft['quizzes'][b-1]['topic']
                            for a, b in report['similar_pairs']))

    def test_cli_prints_only_counts_not_questions_sources_or_answers(self):
        draft = self.draft()
        draft['quizzes'][0]['question'] = 'Privates Beispiel darf nicht in Logs erscheinen?'
        draft['quizzes'][0]['sources'][1] = 'Beleg: Geheimer Testtext darf nicht im Log stehen'
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'draft.json'
            path.write_text(json.dumps(draft, ensure_ascii=False), encoding='utf-8')
            result = subprocess.run([sys.executable, str(ROOT / 'review_checks.py'), str(path)],
                                    capture_output=True, text=True, check=True)
        self.assertIn('Fragen: 50; Themen: 5', result.stdout)
        self.assertIn('keine fachliche Prüfung', result.stdout)
        self.assertNotIn('Privates Beispiel', result.stdout + result.stderr)
        self.assertNotIn('Geheimer Testtext', result.stdout + result.stderr)

    def test_rejects_incomplete_draft(self):
        draft = self.draft()
        draft['quizzes'].pop()
        with self.assertRaises(ValueError):
            review_checks.analyze(draft)


if __name__ == '__main__':
    unittest.main()
