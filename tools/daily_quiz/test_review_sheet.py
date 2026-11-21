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
        self.assertEqual(page.count('Prüfliste für diese Frage'), 50)
        self.assertEqual(page.count('Beanstandet / offen – nicht freigeben'), 50)
        self.assertEqual(page.count('Notizen / Fundstelle:'), 50)
        for number in range(1, 51):
            self.assertIn(f'id="frage-{number}"', page)
            self.assertIn(f'href="#frage-{number}"', page)
        self.assertIn('Die Kästchen sind nur eine Druckhilfe und werden nicht gespeichert.', page)
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
        self.assertNotIn('<script', page)
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
    def config(self, values, *args):
        with tempfile.TemporaryDirectory() as temp:
            output, summary = Path(temp) / 'output', Path(temp) / 'summary'
            env = {k: v for k, v in os.environ.items() if k not in ('ENABLED', 'CONFIRMED', 'FOLDER', 'MODEL', 'GOOGLE_CREDS', 'GEMINI_API_KEY')}
            env.update(values, GITHUB_OUTPUT=str(output), GITHUB_STEP_SUMMARY=str(summary))
            result = subprocess.run([sys.executable, str(ROOT / 'check_config.py'), *args], env=env, capture_output=True, text=True)
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

    def test_manual_test_requires_explicit_confirmation_and_complete_config(self):
        configured = {'FOLDER':'folder', 'MODEL':'model', 'GOOGLE_CREDS':'secret1', 'GEMINI_API_KEY':'secret2'}
        for confirmation in ('', 'false', 'TRUE'):
            result, output, summary = self.config({**configured, 'CONFIRMED':confirmation}, '--manual-test')
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output, 'ready=false\n')
            self.assertIn('nicht bestätigt', summary)
            self.assertNotIn('secret', result.stdout + result.stderr + summary)
        result, output, summary = self.config({'CONFIRMED':'true'}, '--manual-test')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=false\n')
        self.assertIn('unvollständig', summary)
        result, output, summary = self.config({**configured, 'CONFIRMED':'true', 'ENABLED':'false'}, '--manual-test')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=true\n')
        self.assertIn('noch nicht geprüft', summary)
        self.assertNotIn('secret', result.stdout + result.stderr + summary)

    def test_manual_draft_requires_confirmation_and_does_not_enable_schedule(self):
        configured = {'FOLDER':'folder', 'MODEL':'model', 'GOOGLE_CREDS':'secret1', 'GEMINI_API_KEY':'secret2'}
        result, output, _ = self.config(configured, '--manual-draft')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=false\n')
        result, output, summary = self.config({**configured, 'CONFIRMED':'true', 'ENABLED':'false'}, '--manual-draft')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(output, 'ready=true\n')
        self.assertIn('Manueller Entwurf', summary)
        self.assertNotIn('secret', result.stdout + result.stderr + summary)
        result, _, _ = self.config({'CONFIRMED':'true'}, '--manual-draft')
        self.assertNotEqual(result.returncode, 0)

    def test_manual_workflow_is_separate_from_publish_and_scheduled_generation(self):
        workflows = ROOT.parents[1] / '.github' / 'workflows'
        manual = (workflows / 'quiz-manual-test.yml').read_text()
        publisher = (workflows / 'publish-quiz.yml').read_text()
        self.assertIn('workflow_dispatch:', manual)
        self.assertNotIn('schedule:', manual)
        self.assertNotIn('QUIZ_GENERATION_ENABLED', manual)
        self.assertIn('default: false', manual)
        self.assertIn('check_config.py --manual-test', manual)
        self.assertIn('name: tagesquiz-test-nicht-veroeffentlichen', manual)
        self.assertNotIn('CONTENT_PUBLISH_TOKEN', manual)
        self.assertNotIn('quiz_pipeline.py publish', manual)
        self.assertIn('--name tagesquiz-entwurf', publisher)
        self.assertNotIn('tagesquiz-test-nicht-veroeffentlichen', publisher)


if __name__ == '__main__':
    unittest.main()
