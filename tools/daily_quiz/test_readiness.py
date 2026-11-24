import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
WORKFLOW = ROOT.parents[1] / '.github' / 'workflows' / 'quiz-readiness.yml'
KEYS = ('ENABLED', 'FOLDER', 'MODEL', 'GOOGLE_CREDS', 'GEMINI_API_KEY')


class ReadinessTests(unittest.TestCase):
    def run_report(self, **values):
        with tempfile.TemporaryDirectory() as temp:
            summary = Path(temp) / 'summary'
            env = {key: value for key, value in os.environ.items() if key not in KEYS}
            env.update(values, GITHUB_STEP_SUMMARY=str(summary))
            result = subprocess.run([sys.executable, str(ROOT / 'check_readiness.py')],
                                    env=env, capture_output=True, text=True, timeout=5)
            return result, summary.read_text()

    def test_disabled_reports_missing_without_enabling_or_failing(self):
        for value in ('', 'false'):
            result, summary = self.run_report(ENABLED=value)
            self.assertEqual(result.returncode, 0)
            self.assertIn('deaktiviert', summary)
            self.assertIn('Variable LEARNING_DRIVE_FOLDER_ID: fehlt oder ist leer', summary)
            self.assertIn('Secret GEMINI_API_KEY: fehlt oder ist leer', summary)
            self.assertNotIn('::error', result.stdout)

    def test_enabled_requires_all_values_without_showing_them(self):
        secret = 'nonpublic-secret::error::\nInjected value'
        settings = {'ENABLED': 'true', 'FOLDER': 'private-folder', 'MODEL': 'private-model',
                    'GOOGLE_CREDS': secret, 'GEMINI_API_KEY': 'other-private-key'}
        result, summary = self.run_report(**settings)
        self.assertEqual(result.returncode, 0)
        self.assertIn('Generierungsschalter: aktiviert', summary)
        self.assertIn('Secret GEMINI_API_KEY: vorhanden', summary)
        self.assertIn('::notice title=Tagesquiz::Secret GEMINI_API_KEY: vorhanden.', result.stdout)
        self.assertIn('::notice title=Tagesquiz::Generierungsschalter: aktiviert.', result.stdout)
        for value in (secret, 'private-folder', 'private-model', 'other-private-key', 'Injected value'):
            self.assertNotIn(value, result.stdout + result.stderr + summary)
        missing = dict(settings, GEMINI_API_KEY='  ')
        result, summary = self.run_report(**missing)
        self.assertEqual(result.returncode, 1)
        self.assertIn('Secret GEMINI_API_KEY: fehlt oder ist leer', summary)
        self.assertIn('::error title=Tagesquiz::', result.stdout)
        self.assertNotIn(secret, result.stdout + result.stderr + summary)

    def test_case_insensitive_true_and_false_are_unambiguous(self):
        settings = {'FOLDER': 'folder', 'MODEL': 'model', 'GOOGLE_CREDS': 'secret', 'GEMINI_API_KEY': 'key'}
        for value in ('TRUE', 'True', ' tRuE '):
            result, summary = self.run_report(**dict(settings, ENABLED=value))
            self.assertEqual(result.returncode, 0)
            self.assertIn('Generierungsschalter: aktiviert', summary)
        for value in ('FALSE', 'False', ' fAlSe '):
            result, summary = self.run_report(ENABLED=value)
            self.assertEqual(result.returncode, 0)
            self.assertIn('deaktiviert', summary)

    def test_invalid_switch_is_rejected_without_echoing_value(self):
        result, summary = self.run_report(ENABLED='true\n::error::secret')
        self.assertEqual(result.returncode, 1)
        self.assertIn('ungültig', summary)
        self.assertNotIn('secret', result.stdout + result.stderr + summary)

    def test_invalid_switch_has_safe_actionable_category_without_echo(self):
        for value, hint in [('"true"', 'Anführungszeichen'),
                            ("'false'", 'Anführungszeichen'), ('yes', 'Ja/Nein-Werte'),
                            ('private::error::value', 'anderes ungültiges Format')]:
            with self.subTest(value=value):
                result, summary = self.run_report(ENABLED=value)
                self.assertEqual(result.returncode, 1)
                self.assertIn(hint, summary)
                self.assertIn('::notice title=Tagesquiz::Hinweis zum Generierungsschalter:', result.stdout)
                if value.startswith('private'):
                    self.assertNotIn(value, result.stdout + result.stderr + summary)

    def test_workflow_is_manual_and_contains_no_generation_or_publication(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertIn('contents: read', workflow)
        self.assertIn('refs/heads/main', workflow)
        self.assertLess(workflow.index('refs/heads/main'), workflow.index('secrets.LEARNING_DRIVE_CREDENTIALS'))
        self.assertIn('check_readiness.py', workflow)
        for text in ('quiz_pipeline.py', 'publish', 'CONTENT_PUBLISH_TOKEN', 'gh variable set', 'GITHUB_OUTPUT'):
            self.assertNotIn(text, workflow)


if __name__ == '__main__':
    unittest.main()
