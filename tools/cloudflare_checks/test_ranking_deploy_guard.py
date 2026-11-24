"""Ensure the experimental ranking cannot deploy on a push or without confirmation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RankingDeployGuardTests(unittest.TestCase):
    def test_dispatch_only_and_all_three_guards_required(self):
        workflow = (ROOT / '.github/workflows/deploy-ranking-worker.yml').read_text()
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertNotIn('  push:', workflow)
        self.assertNotIn('  pull_request:', workflow)
        self.assertIn('      confirm:', workflow)
        self.assertIn('        type: boolean', workflow)
        self.assertIn('        required: true', workflow)
        self.assertIn('        default: false', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && vars.ENABLE_QUIZ_RANKING_DEPLOY == 'true' && inputs.confirm == true", workflow)
        self.assertIn('  contents: read', workflow)
        self.assertIn('          persist-credentials: false', workflow)


if __name__ == '__main__':
    unittest.main()
