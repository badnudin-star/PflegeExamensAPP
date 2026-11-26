import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from verify_update_buildconfig import verify

from check_update_release import check

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a' * 40
RUN = {'path':'.github/workflows/internal-release.yml', 'head_branch':'main', 'head_sha':SHA,
       'event':'workflow_dispatch', 'status':'completed', 'conclusion':'success'}
GRADLE = '  versionCode = 19\n  versionName = "3.0.8"\n'


class ReleaseGuardTests(unittest.TestCase):
    def test_only_successful_reviewed_main_run_at_exact_sha(self):
        self.assertEqual(check(RUN, SHA, GRADLE), (19, '3.0.8'))
        for field, value in [('path', '.github/workflows/other.yml'), ('head_branch','feature'),
                             ('head_sha','b'*40), ('event','push'), ('status','in_progress'), ('conclusion','failure')]:
            changed = copy.deepcopy(RUN); changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): check(changed, SHA, GRADLE)
        for gradle in ['versionCode = 0\nversionName = "3.0.8"', GRADLE + GRADLE, 'versionCode = 19\nversionName = "3.0.8-rc"']:
            with self.assertRaises(ValueError): check(RUN, SHA, gradle)

    def test_signed_buildconfig_requires_enabled_flag_once(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / 'BuildConfig.java'
            path.write_text('public static final boolean ENABLE_APP_UPDATES = true;')
            verify(path)
            path.write_text('public static final boolean ENABLE_APP_UPDATES = false;')
            with self.assertRaises(ValueError): verify(path)

    def test_update_installer_is_scoped_and_does_not_uninstall(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        paths = (ROOT / 'app/src/main/res/xml/file_paths.xml').read_text()
        manager = (ROOT / 'app/src/main/java/de/pflegeexamen/app/data/update/AppUpdateManager.kt').read_text()
        self.assertIn('android.permission.REQUEST_INSTALL_PACKAGES', manifest)
        self.assertIn('path="app_updates/"', paths)
        self.assertIn('canRequestPackageInstalls()', manager)
        self.assertIn('FLAG_GRANT_READ_URI_PERMISSION', manager)
        self.assertNotIn('ACTION_DELETE', manager)
        self.assertNotIn('ACTION_UNINSTALL_PACKAGE', manager)

    def test_publication_is_explicit_main_only_and_checks_certificate(self):
        workflow = (ROOT / '.github/workflows/publish-app-update.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirm_public == true", workflow)
        self.assertIn('compare_release_certificates.py', workflow)
        self.assertIn('check_update_release.py', workflow)
        self.assertIn('gh run download', workflow)
        self.assertIn('gh release create', workflow)
        self.assertIn('--verify-tag --draft', workflow)
        self.assertNotIn('action-gh-release', (ROOT / '.github/workflows/android-apk.yml').read_text())


if __name__ == '__main__': unittest.main()
