import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parent
WORKFLOWS = ROOT.parents[1] / '.github' / 'workflows'


class ManualApprovalTests(unittest.TestCase):
    def guard(self, **values):
        env = {k: v for k, v in os.environ.items() if k not in ('CONFIRMED', 'RUN_ID', 'HASH')}
        env.update(values)
        return subprocess.run([sys.executable, str(ROOT / 'check_review_approval.py')], env=env,
                              capture_output=True, text=True)

    def test_confirmation_and_correctly_formed_inputs_required(self):
        valid = {'CONFIRMED':'true', 'RUN_ID':'44943386512', 'HASH':'a'*64}
        self.assertEqual(self.guard(**valid).returncode, 0)
        for changes in ({'CONFIRMED':'false'}, {'CONFIRMED':''}, {'CONFIRMED':'TRUE'},
                        {'RUN_ID':'0'}, {'RUN_ID':'3;echo leak'}, {'RUN_ID':'9'*21},
                        {'HASH':'a'*63}, {'HASH':'g'*64}, {'HASH':'secret\nword'}):
            with self.subTest(changes=changes):
                result = self.guard(**(valid | changes))
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('secret', result.stdout + result.stderr)
        self.assertEqual(self.guard(**(valid | {'HASH':'ABCDEF' * 10 + 'ABCD'})).returncode, 0)

    def test_manual_draft_is_separate_from_schedule_and_publication(self):
        workflow = (WORKFLOWS / 'quiz-manual-draft.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertNotIn('QUIZ_GENERATION_ENABLED', workflow)
        self.assertIn('default: false', workflow)
        self.assertIn('check_config.py --manual-draft', workflow)
        self.assertIn('name: tagesquiz-entwurf', workflow)
        self.assertNotIn('CONTENT_PUBLISH_TOKEN', workflow)
        self.assertNotIn('quiz_pipeline.py publish', workflow)
        self.assertIn('review_sheet.py', workflow)

    def test_publisher_guard_runs_before_download_and_publication(self):
        workflow = (WORKFLOWS / 'publish-quiz.yml').read_text()
        self.assertIn('review_confirmed:', workflow)
        self.assertIn('default: false', workflow)
        self.assertLess(workflow.index('check_review_approval.py'), workflow.index('gh run download'))
        self.assertLess(workflow.index('gh run download'), workflow.index('quiz_pipeline.py publish'))
        self.assertIn('--name tagesquiz-entwurf', workflow)
        self.assertNotIn('tagesquiz-test-nicht-veroeffentlichen', workflow)


if __name__ == '__main__':
    unittest.main()
