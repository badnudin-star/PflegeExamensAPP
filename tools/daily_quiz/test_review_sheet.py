import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import test_quiz_pipeline
import review_sheet

ROOT = Path(__file__).resolve().parent


class ReviewSheetTests(unittest.TestCase):
    def draft(self):
        return test_quiz_pipeline.QuizTests().draft()

    def test_review_displays_all_questions_and_exact_json_hash(self):
        draft = self.draft()
        raw = (json.dumps(draft, ensure_ascii=False, indent=2) + '\n').encode()
        page = review_sheet.render(raw)
        self.assertIn(hashlib.sha256(raw).hexdigest(), page)
        self.assertIn('Tagesquiz 2026-11-18', page)
        self.assertEqual(page.count('class="question"'), 50)
        self.assertEqual(page.count('– als richtig markiert'), 50)
        self.assertEqual(page.count('fachlich geprüft</p>'), 50)
        for q in draft['quizzes']:
            self.assertIn(q['question'], page)
            self.assertIn(q['sources'][1], page)
        self.assertNotIn('<script', page)

    def test_html_is_escaped_and_invalid_draft_rejected(self):
        draft = self.draft()
        draft['quizzes'][0]['question'] = '<script>alert("x")</script>'
        page = review_sheet.render(json.dumps(draft).encode())
        self.assertNotIn('<script', page)
        self.assertIn('&lt;script&gt;', page)
        draft['quizzes'].pop()
        with self.assertRaises(ValueError):
            review_sheet.render(json.dumps(draft).encode())

    def test_cli_keeps_original_and_rejects_same_output(self):
        raw = json.dumps(self.draft(), ensure_ascii=False).encode()
        with tempfile.TemporaryDirectory() as temp:
            draft = Path(temp) / 'quiz-draft.json'
            output = Path(temp) / 'quiz-review.html'
            draft.write_bytes(raw)
            command = [sys.executable, str(ROOT / 'review_sheet.py'), str(draft)]
            subprocess.run(command + [str(output)], check=True, capture_output=True)
            self.assertEqual(draft.read_bytes(), raw)
            self.assertIn(hashlib.sha256(raw).hexdigest(), output.read_text())
            result = subprocess.run(command + [str(draft)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(draft.read_bytes(), raw)


class ConfigTests(unittest.TestCase):
    def config(self, values):
        with tempfile.TemporaryDirectory() as temp:
            output, summary = Path(temp) / 'output', Path(temp) / 'summary'
            env = {k: v for k, v in os.environ.items() if k not in ('ENABLED', 'FOLDER', 'MODEL', 'GOOGLE_CREDS', 'GEMINI_API_KEY')}
            env.update(values, GITHUB_OUTPUT=str(output), GITHUB_STEP_SUMMARY=str(summary))
            result = subprocess.run([sys.executable, str(ROOT / 'check_config.py')], env=env, capture_output=True, text=True)
            return result, output.read_text(), summary.read_text()

    def test_disabled_is_explicit_and_does_not_require_credentials(self):
        result, output, summary = self.config({})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=false\n')
        self.assertIn('deaktiviert', summary)

    def test_opted_in_missing_configuration_fails_without_disclosing_secrets(self):
        result, output, summary = self.config({'ENABLED':'true','GOOGLE_CREDS':'sensitive-value'})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=false\n')
        self.assertIn('unvollständig', summary)
        self.assertNotIn('sensitive-value', result.stdout + result.stderr + summary)
        self.assertNotEqual(self.config({'ENABLED':'TRUE'})[0].returncode, 0)

    def test_configured_is_ready_but_does_not_claim_connections_work(self):
        result, output, summary = self.config({'ENABLED':'true', 'FOLDER':'folder', 'MODEL':'model', 'GOOGLE_CREDS':'secret1', 'GEMINI_API_KEY':'secret2'})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=true\n')
        self.assertIn('noch nicht geprüft', summary)
        self.assertNotIn('secret', result.stdout + result.stderr + summary)


if __name__ == '__main__':
    unittest.main()
