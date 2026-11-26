import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from bundle_note_pdfs import bundle
from verify_pdf_bundle import verify
from import_notes import TOPICS, topic_candidates


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.indexed = {name: {'name': name} for i in range(len(TOPICS)) for name in topic_candidates(i)[0]}
        self.catalog = {'notes': []}
        for i, (_, title) in enumerate(TOPICS):
            main, simple = topic_candidates(i)[0]
            self.catalog['notes'].append({'topic': title, 'formatVersion': 2, 'detailed': main,
                                          'balanced': main, 'short': simple,
                                          'sources': ['Lernzettel: ' + main, 'Lernzettel: ' + simple]})

    def test_bundles_both_versions_and_text_hashes(self):
        with TemporaryDirectory() as tmp, patch('bundle_note_pdfs.extract_pdf', side_effect=lambda b: b.decode()):
            path = Path(tmp) / 'pdfs'
            count, size = bundle(self.catalog, self.indexed, lambda f: f['name'].encode(), path)
            manifest = json.loads((path / 'manifest.json').read_text())
            self.assertEqual(count, len(self.indexed))
            self.assertGreater(size, 0)
            self.assertEqual(set(manifest), set(self.indexed))
            for name, digest in manifest.items():
                self.assertEqual((path / name).read_bytes(), name.encode())
                self.assertEqual(digest, hashlib.sha256(name.encode()).hexdigest())
            with self.assertRaises(ValueError): bundle(self.catalog, self.indexed, lambda f: f['name'].encode(), path)

    def test_apk_verification_detects_missing_or_changed_files(self):
        with TemporaryDirectory() as tmp, patch('bundle_note_pdfs.extract_pdf', side_effect=lambda b: b.decode()):
            path = Path(tmp) / 'pdfs'
            bundle(self.catalog, self.indexed, lambda f: f['name'].encode(), path)
            apk = Path(tmp) / 'app.apk'
            with ZipFile(apk, 'w') as archive:
                for file in path.iterdir(): archive.write(file, 'assets/private/note_pdfs/' + file.name)
            self.assertEqual(verify(apk, path), len(self.indexed))
            (path / next(iter(self.indexed))).write_bytes(b'changed')
            with self.assertRaises(ValueError): verify(apk, path)

    def test_changed_source_or_partial_catalog_never_bundles(self):
        with TemporaryDirectory() as tmp, patch('bundle_note_pdfs.extract_pdf', side_effect=lambda b: b.decode()):
            path = Path(tmp) / 'pdfs'
            self.catalog['notes'][0]['short'] = 'stale text'
            with self.assertRaises(ValueError): bundle(self.catalog, self.indexed, lambda f: f['name'].encode(), path)
            self.assertFalse(path.exists())
            self.catalog['notes'].pop()
            with self.assertRaises(ValueError): bundle(self.catalog, self.indexed, lambda f: f['name'].encode(), path)
            self.assertFalse(path.exists())


if __name__ == '__main__': unittest.main()
