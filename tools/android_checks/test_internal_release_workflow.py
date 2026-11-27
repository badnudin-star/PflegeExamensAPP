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
        self.assertIn('./gradlew assembleRelease --no-daemon --quiet -PrankingUrl=https://pflege-examen-ranking.badnudin.workers.dev/leaderboard -PenableRankedParticipation=true', workflow)
        self.assertIn('verify_ranking_buildconfig.py', workflow)
        self.assertIn('compare_release_certificates.py', workflow)
        self.assertIn('131ca778b5fac3e9d0cb30b3b49b6db476ed7745572f3c038805952672967425', workflow)
        self.assertIn("versionCode='20' versionName='3.0.9'", workflow)
        self.assertIn('-PenableAppUpdates=true', workflow)
        self.assertIn('bundle_note_pdfs.py', workflow)
        self.assertIn('verify_pdf_bundle.py', workflow)
        self.assertIn("if: steps.drive_pdfs.outcome == 'failure'", workflow)
        self.assertIn('ios/tools/bundle_public_apk_pdfs.py', workflow)
        self.assertIn('app/src/main/assets/learning_content.json app/src/main/assets/private/note_pdfs', workflow)
        self.assertIn('rm -f "$GOOGLE_APPLICATION_CREDENTIALS"', workflow)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('versionCode = 20', gradle)
        self.assertIn('versionName = "3.0.9"', gradle)
        self.assertNotIn('action-gh-release', workflow)
        self.assertNotIn('set -x', workflow)
        self.assertNotIn('--stacktrace', workflow)
        for name in ('PFLEGE_RELEASE_KEYSTORE_BASE64', 'PFLEGE_RELEASE_STORE_PASSWORD',
                     'PFLEGE_RELEASE_KEY_ALIAS', 'PFLEGE_RELEASE_KEY_PASSWORD'):
            self.assertIn('secrets.' + name, workflow)


if __name__ == '__main__': unittest.main()
