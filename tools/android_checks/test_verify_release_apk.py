import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_release_apk import verify


class ReleaseApkTests(unittest.TestCase):
    def test_catalog_must_match_exactly(self):
        with tempfile.TemporaryDirectory() as temp:
            catalog, apk = Path(temp)/'catalog.json', Path(temp)/'app.apk'
            catalog.write_bytes(b'{"synthetic":true}')
            with zipfile.ZipFile(apk, 'w') as z: z.writestr('assets/learning_content.json',catalog.read_bytes())
            self.assertEqual(verify(apk,catalog),hashlib.sha256(apk.read_bytes()).hexdigest())
            catalog.write_bytes(b'other')
            with self.assertRaises(ValueError): verify(apk,catalog)
            with zipfile.ZipFile(apk, 'w') as z: z.writestr('other',b'')
            with self.assertRaises(KeyError): verify(apk,catalog)


if __name__ == '__main__': unittest.main()
