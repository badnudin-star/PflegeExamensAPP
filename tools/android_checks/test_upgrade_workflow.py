import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/android-upgrade-check.yml'
SCRIPT = ROOT / 'tools/android_checks/upgrade_smoke.sh'


class UpgradeWorkflowTests(unittest.TestCase):
    def test_manual_only_and_trusted_apk_origin(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirm == true", workflow)
        self.assertIn('internal-release.yml', workflow)
        self.assertIn('.conclusion == "success"', workflow)
        self.assertIn('compare_release_certificates.py', workflow)
        self.assertIn('versionCode=\'15\' versionName=\'3.0.4\'', workflow)
        self.assertIn('versionCode=\'16\' versionName=\'3.0.5\'', workflow)
        self.assertNotIn('secrets.', workflow)

    def test_script_is_shell_valid_and_restricts_to_emulator(self):
        subprocess.run(['bash', '-n', str(SCRIPT)], check=True)
        script = SCRIPT.read_text()
        self.assertLess(script.index('emulator-*)'), script.index('\nadb root'))
        self.assertIn('adb install -r "$2"', script)
        self.assertIn('synthetic-upgrade-probe', script)
        self.assertIn('cmp "$RUNNER_TEMP/upgrade-card-progress.xml"', script)
        self.assertNotIn('adb uninstall', script)


if __name__ == '__main__':
    unittest.main()
