import contextlib
import io
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import ranking_backup as backup
import ranking_backup_artifact as artifact
from test_ranking_backup import fixture, SOURCE, NAME

PASSWORD = 'a' * 43  # Synthetic only; length is not evidence of randomness.


class ArtifactBackupTests(unittest.TestCase):
    def env(self, tmp):
        return {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'secret',
                'RANKING_BACKUP_PASSPHRASE':PASSWORD, 'CONFIRM_BACKUP':'true',
                'GITHUB_REF':'refs/heads/main', 'GITHUB_EVENT_NAME':'workflow_dispatch', 'RUNNER_TEMP':tmp}

    def test_ref_confirmation_password_and_destination_guard_before_any_read(self):
        with TemporaryDirectory() as tmp:
            for name, value in [('GITHUB_REF', 'refs/heads/other'), ('GITHUB_EVENT_NAME', 'push'),
                                ('CONFIRM_BACKUP', 'false'), ('RANKING_BACKUP_PASSPHRASE', 'weak'),
                                ('CLOUDFLARE_ACCOUNT_ID', ''), ('RUNNER_TEMP', 'relative')]:
                env = self.env(tmp); env[name] = value
                with self.subTest(name=name), patch.object(artifact.backup, 'capture') as capture:
                    with self.assertRaises(Exception): artifact.run(env, None)
                    capture.assert_not_called()
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_synthetic_readback_and_no_secrets_or_contents_in_output(self):
        with TemporaryDirectory() as tmp, patch.object(artifact, 'DB_ID', SOURCE), patch.object(artifact, 'NAME', NAME), \
             patch.object(artifact.backup, 'capture', return_value=fixture()) as capture, contextlib.redirect_stdout(io.StringIO()) as out:
            artifact.run(self.env(tmp), None)
            path = Path(tmp) / 'ranking-backup.rankbackup'
            blob = path.read_bytes()
            self.assertEqual(backup.decrypt(blob, PASSWORD, SOURCE, NAME), fixture())
            self.assertNotIn(b'Synthetic-Private', blob)
            self.assertEqual(capture.call_count, 1)
            self.assertNotIn('Synthetic-Private', out.getvalue())
            self.assertNotIn(PASSWORD, out.getvalue())
            self.assertNotIn('secret', out.getvalue())
            with self.assertRaises(Exception): artifact.run(self.env(tmp), None)
            self.assertEqual(path.read_bytes(), blob)
            self.assertEqual(capture.call_count, 1)

    def test_diagnostic_reports_only_fixed_category_and_never_exception_text(self):
        with TemporaryDirectory() as tmp:
            for value, code in [('', 'password_missing'), ('a real confidential bad value', 'password_format')]:
                env = self.env(tmp); env['RANKING_BACKUP_PASSPHRASE'] = value
                with self.subTest(code=code), patch.dict('os.environ', env, clear=True), contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(artifact.main(), 1)
                    self.assertIn(artifact.MESSAGES[code], output.getvalue())
                    if value: self.assertNotIn(value, output.getvalue())
            with patch.dict('os.environ', self.env(tmp), clear=True), \
                 patch.object(artifact.backup, 'capture', side_effect=Exception('private sensitive detail')), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(artifact.main(), 1)
                self.assertIn(artifact.MESSAGES['capture'], output.getvalue())
                self.assertNotIn('private sensitive detail', output.getvalue())

    def test_workflow_is_manual_main_only_with_encrypted_artifact_and_cleanup(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/ranking-backup-artifact.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirm == true", workflow)
        self.assertIn('secrets.RANKING_BACKUP_PASSPHRASE', workflow)
        self.assertIn('retention-days: 7', workflow)
        self.assertIn('rm -f "$RUNNER_TEMP/ranking-backup.rankbackup"', workflow)
        self.assertIn('if: always()', workflow)


if __name__ == '__main__': unittest.main()
