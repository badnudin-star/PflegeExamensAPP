import json
import plistlib
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_ipa import verify


class VerifyIpaTests(unittest.TestCase):
    def make(self, path, platform='iPhoneOS', cpu=0x0100000c, extra=None, executable_suffix=b''):
        prefix = 'Payload/PflegeExamen.app/'
        info = {'CFBundleIdentifier': 'de.pflegeexamen.ios.preview',
                'CFBundleExecutable': 'PflegeExamen', 'CFBundleSupportedPlatforms': [platform]}
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr(prefix + 'Info.plist', plistlib.dumps(info))
            z.writestr(prefix + 'PflegeExamen', struct.pack('<III', 0xfeedfacf, cpu, 0) + executable_suffix)
            z.writestr(prefix + 'learning_content.json', json.dumps({'schemaVersion': 1, 'notes': [1]}))
            if extra: z.writestr(extra, 'x')

    def test_pdf_bundle_must_match(self):
        with tempfile.TemporaryDirectory() as d:
            ipa = Path(d) / 'app.ipa'
            pdfs = Path(d) / 'pdfs'; pdfs.mkdir()
            (pdfs / 'manifest.json').write_text('{}')
            (pdfs / 'note.pdf').write_bytes(b'PDF')
            self.make(ipa)
            with zipfile.ZipFile(ipa, 'a') as z:
                for item in pdfs.iterdir():
                    z.write(item, 'Payload/PflegeExamen.app/note_pdfs/' + item.name)
            verify(ipa, pdfs)
            (pdfs / 'note.pdf').write_bytes(b'changed')
            with self.assertRaises(ValueError): verify(ipa, pdfs)

    def test_simulator_only_entry_point_cannot_ship(self):
        with tempfile.TemporaryDirectory() as d:
            ipa = Path(d) / 'app.ipa'
            self.make(ipa, executable_suffix=b'-synthetic-ranking-ui')
            with self.assertRaises(ValueError): verify(ipa)

    def test_package_and_rejections(self):
        with tempfile.TemporaryDirectory() as d:
            ipa = Path(d) / 'app.ipa'
            self.make(ipa)
            verify(ipa)
            for kwargs in ({'platform': 'iPhoneSimulator'}, {'cpu': 0x01000007},
                           {'extra': 'Payload/PflegeExamen.app/embedded.mobileprovision'},
                           {'extra': 'Payload/another.app/file'}, {'extra': 'Payload/PflegeExamen.app/../../escape'}):
                with self.subTest(kwargs=kwargs):
                    self.make(ipa, **kwargs)
                    with self.assertRaises(ValueError): verify(ipa)

if __name__ == '__main__': unittest.main()
