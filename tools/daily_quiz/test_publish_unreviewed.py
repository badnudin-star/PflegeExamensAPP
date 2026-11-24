import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import publish_unreviewed as unreviewed
import quiz_pipeline as qp
import test_quiz_pipeline
from test_offline_flow import BASE, FakeHttp

ROOT = Path(__file__).resolve().parents[2]


class UnreviewedPublicationTests(unittest.TestCase):
    def setUp(self):
        self.draft = test_quiz_pipeline.QuizTests().draft()
        self.raw = json.dumps(self.draft, ensure_ascii=False).encode()
        self.hash = hashlib.sha256(self.raw).hexdigest()

    def test_only_adds_visible_warning_to_every_question(self):
        original = copy.deepcopy(self.draft)
        result = unreviewed.prepare(self.raw, self.hash, 'true')
        self.assertEqual(self.draft, original)
        self.assertEqual(result['date'], self.draft['date'])
        for marked, original in zip(result['quizzes'], self.draft['quizzes']):
            self.assertEqual(marked['question'], unreviewed.WARNING + original['question'])
            self.assertEqual({k: v for k, v in marked.items() if k != 'question'},
                             {k: v for k, v in original.items() if k != 'question'})
        qp.validate_draft(result)

    def test_guard_rejects_bad_confirmation_hash_and_incomplete_draft(self):
        for raw, hash_, confirmed in ((self.raw, self.hash, 'false'), (self.raw, self.hash, ''),
                                      (self.raw, '0' * 64, 'true'), (self.raw, 'x' * 64, 'true'),
                                      (b'x' * (unreviewed.MAX_DRAFT_BYTES + 1), self.hash, 'true')):
            with self.subTest(confirmed=confirmed), self.assertRaises(ValueError):
                unreviewed.prepare(raw, hash_, confirmed)
        incomplete = copy.deepcopy(self.draft)
        incomplete['quizzes'].pop()
        raw = json.dumps(incomplete).encode()
        with self.assertRaises(ValueError):
            unreviewed.prepare(raw, hashlib.sha256(raw).hexdigest(), 'true')

    def test_existing_publish_conflict_safety_and_warning_survive(self):
        http = FakeHttp()
        marked = unreviewed.prepare(self.raw, self.hash, 'true')
        with patch.object(qp, 'read_remote', return_value=copy.deepcopy(BASE)):
            qp.publish(http, 'https://content.example', 'x' * 32, marked, BASE)
        self.assertEqual(len(http.puts), 1)
        sent = json.loads(http.puts[0][1]['data'])
        self.assertTrue(all(q['question'].startswith(unreviewed.WARNING) for q in sent['quizzes']))
        self.assertEqual(http.puts[0][1]['headers']['If-Match'], '3')
        conflict = {**BASE, 'quizzes': marked['quizzes']}
        http = FakeHttp()
        with patch.object(qp, 'read_remote', return_value=conflict), self.assertRaises(ValueError):
            qp.publish(http, 'https://content.example', 'x' * 32, marked, BASE)
        self.assertEqual(http.puts, [])

    def test_separate_explicit_manual_workflow_does_not_claim_review(self):
        workflow = (ROOT / '.github/workflows/publish-unreviewed-quiz.yml').read_text()
        self.assertIn('unreviewed_confirmed:', workflow)
        self.assertIn('default: false', workflow)
        self.assertIn('OHNE fachliche Prüfung', workflow)
        self.assertIn('refs/heads/main', workflow)
        self.assertIn('publish_unreviewed.py', workflow)
        self.assertNotIn('review_confirmed:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertNotIn('GEMINI_API_KEY', workflow)
        self.assertLess(workflow.index('Freigabe und Ursprung'), workflow.index('gh run download'))
        self.assertLess(workflow.index('gh run download'), workflow.index('publish_unreviewed.py'))


if __name__ == '__main__':
    unittest.main()
