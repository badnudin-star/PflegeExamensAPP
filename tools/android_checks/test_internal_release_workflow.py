"""Guard basic release workflow boundaries; end-to-end signing runs separately in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InternalReleaseWorkflowTests(unittest.TestCase):
    def test_manual_main_only_with_isolated_environment_and_exact_apk_artifact(self):
        workflow = (ROOT / '.github/workflows/internal-release.yml').read_text()
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertNotIn('  push:', workflow)
        self.assertNotIn('  pull_request:', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirm == true", workflow)
        self.assertIn('environment: internal-release', workflow)
        self.assertIn('needs: preflight', workflow)
        self.assertIn('tools/android_checks/check_release_request.py', workflow)
        # Secrets must only be available in the protected release job, never in preflight.
        preflight = workflow.split('  preflight:', 1)[1].split('  release:', 1)[0]
        self.assertNotIn('secrets.', preflight)
        self.assertIn('persist-credentials: false', workflow)
        self.assertIn('contents: read', workflow)
        self.assertIn('if: always()', workflow)
        self.assertIn('rm -f "$RUNNER_TEMP/pflege-release-signing.keystore"', workflow)
        self.assertIn('path: app/build/outputs/apk/release/app-release.apk', workflow)
        self.assertNotIn('action-gh-release', workflow)
        self.assertNotIn('set -x', workflow)
        self.assertNotIn('--stacktrace', workflow)
        for name in ('PFLEGE_RELEASE_KEYSTORE_BASE64', 'PFLEGE_RELEASE_STORE_PASSWORD',
                     'PFLEGE_RELEASE_KEY_ALIAS', 'PFLEGE_RELEASE_KEY_PASSWORD'):
            self.assertIn('secrets.' + name, workflow)


if __name__ == '__main__': unittest.main()
