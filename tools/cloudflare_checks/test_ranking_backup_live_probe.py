import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ranking_backup_live_probe as live
from ranking_provision import PreflightError
from test_ranking_backup import fixture

class LiveBackupProbeTests(unittest.TestCase):
    def test_confirmation_required_before_any_live_read(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(live.backup,'capture') as capture, self.assertRaises(PreflightError):
            live.run({'CLOUDFLARE_ACCOUNT_ID':'a'*32,'CLOUDFLARE_API_TOKEN':'secret',
                      'RUNNER_TEMP':directory,'LIVE_BACKUP_PROBE_CONFIRM':'false'},None)
        capture.assert_not_called()

    def test_only_ciphertext_temporary_and_no_private_output(self):
        data=fixture(); data['source_id']=live.DB_ID; data['source_name']=live.NAME
        output=io.StringIO()
        with tempfile.TemporaryDirectory() as directory, patch.object(live.backup,'capture',return_value=data) as capture, contextlib.redirect_stdout(output):
            live.run({'CLOUDFLARE_ACCOUNT_ID':'a'*32,'CLOUDFLARE_API_TOKEN':'secret',
                      'RUNNER_TEMP':directory,'LIVE_BACKUP_PROBE_CONFIRM':'true'},None)
            self.assertEqual(list(Path(directory).iterdir()), [])
        self.assertEqual(capture.call_args.args[-2:],(live.DB_ID,live.NAME))
        self.assertNotIn('Synthetic-Private',output.getvalue())
        self.assertNotIn('secret',output.getvalue())
        self.assertIn('Kein Backup',output.getvalue())

if __name__ == '__main__': unittest.main()
