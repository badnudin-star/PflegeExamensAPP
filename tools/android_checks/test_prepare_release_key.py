import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_release_key import KEY_FILENAME, MAX_KEY_BYTES, prepare


class ReleaseKeyTests(unittest.TestCase):
    def env(self, temp):
        return {'RUNNER_TEMP': temp, 'PFLEGE_RELEASE_KEYSTORE_BASE64': base64.b64encode(b'synthetic-key').decode(),
                'PFLEGE_RELEASE_STORE_PASSWORD':'test', 'PFLEGE_RELEASE_KEY_ALIAS':'test', 'PFLEGE_RELEASE_KEY_PASSWORD':'test'}

    def test_writes_private_exact_bytes_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            path = prepare(self.env(temp))
            self.assertEqual(path.read_bytes(), b'synthetic-key')
            self.assertEqual(path.stat().st_mode & 0o077, 0)
            with self.assertRaises(FileExistsError): prepare(self.env(temp))
            self.assertEqual(path.read_bytes(), b'synthetic-key')

    def test_rejects_missing_invalid_oversized_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            for changes in ({'PFLEGE_RELEASE_STORE_PASSWORD':''}, {'PFLEGE_RELEASE_KEYSTORE_BASE64':'not base64!'},
                            {'PFLEGE_RELEASE_KEYSTORE_BASE64':base64.b64encode(b'x'*(MAX_KEY_BYTES+1)).decode()},
                            {'RUNNER_TEMP':''}):
                with self.subTest(changes=list(changes)), self.assertRaises(ValueError):
                    prepare({**self.env(temp), **changes})
                self.assertFalse((Path(temp)/KEY_FILENAME).exists())

    def test_existing_symlink_is_not_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)/'target'; target.write_bytes(b'unchanged')
            (Path(temp)/KEY_FILENAME).symlink_to(target)
            with self.assertRaises(FileExistsError): prepare(self.env(temp))
            self.assertEqual(target.read_bytes(), b'unchanged')

    def test_failed_write_removes_only_new_incomplete_file(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch('prepare_release_key.os.fsync', side_effect=OSError('simulated')):
                with self.assertRaises(OSError): prepare(self.env(temp))
            self.assertFalse((Path(temp)/KEY_FILENAME).exists())


if __name__ == '__main__': unittest.main()
