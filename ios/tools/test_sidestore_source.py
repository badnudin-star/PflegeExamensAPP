import json
import plistlib
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from make_sidestore_source import make_source

class SourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.ipa = Path(self.temp.name) / 'app.ipa'
        self.make_ipa()

    def make_ipa(self, signed=False, extra=None):
        prefix = 'Payload/PflegeExamen.app/'
        info = {'CFBundleIdentifier':'de.pflegeexamen.ios.preview', 'CFBundleExecutable':'PflegeExamen',
                'CFBundleSupportedPlatforms':['iPhoneOS'], 'CFBundleShortVersionString':'0.1',
                'CFBundleVersion':'1', 'MinimumOSVersion':'16.0'}
        commands = struct.pack('<II', 0x1d, 8) if signed else b''
        binary = struct.pack('<IIIIIIII', 0xfeedfacf, 0x0100000c, 0, 2, int(signed), len(commands), 0, 0) + commands
        with zipfile.ZipFile(self.ipa, 'w') as z:
            z.writestr(prefix+'Info.plist', plistlib.dumps(info))
            z.writestr(prefix+'PflegeExamen', binary)
            z.writestr(prefix+'learning_content.json', json.dumps({'schemaVersion':1, 'notes':[1]}))
            if extra: z.writestr(prefix+extra, 'x')

    def make(self, **overrides):
        args = dict(ipa=self.ipa, download_url='https://example.org/app.ipa', icon_url='https://example.org/icon.png', date='2026-01-01')
        args.update(overrides)
        return make_source(**args)

    def test_extracts_actual_ipa_metadata(self):
        result = self.make()['apps'][0]
        self.assertEqual(result['bundleIdentifier'], 'de.pflegeexamen.ios.preview')
        self.assertEqual(result['versions'][0]['size'], self.ipa.stat().st_size)
        self.assertEqual(result['versions'][0]['version'], '0.1')
        self.assertEqual(result['appPermissions'], {'entitlements': [], 'privacy': {}})

    def test_unsafe_urls_invalid_date_and_signature_rejected(self):
        for kwargs in ({'download_url':'http://example.org/app.ipa'}, {'download_url':'https://user:pw@example.org/app.ipa'},
                       {'icon_url':'https://example.org/icon.png?token=secret'}, {'date':'2026-02-29'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError): self.make(**kwargs)
        self.make_ipa(signed=True)
        with self.assertRaises(ValueError): self.make()
        self.make_ipa(extra='PlugIns/sample.appex/foo')
        with self.assertRaises(ValueError): self.make()

if __name__ == '__main__': unittest.main()
