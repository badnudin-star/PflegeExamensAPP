import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zipfile import ZipFile
from bundle_public_apk_pdfs import extract, PREFIX
from bundle_note_pdfs import bundle
from import_notes import TOPICS, topic_candidates


class PublicPDFBundleTests(unittest.TestCase):
    def test_pinned_apk_and_catalog_are_required(self):
        indexed = {name: {'name':name} for i in range(len(TOPICS)) for name in topic_candidates(i)[0]}
        notes = []
        for i, (_, title) in enumerate(TOPICS):
            main, simple = topic_candidates(i)[0]
            notes.append({'topic':title, 'formatVersion':2, 'detailed':main, 'balanced':main, 'short':simple,
                          'sources':['Lernzettel: '+main, 'Lernzettel: '+simple]})
        catalog = {'notes': notes}
        with TemporaryDirectory() as temp, patch('bundle_note_pdfs.extract_pdf', side_effect=lambda b: b.decode()):
            root = Path(temp)
            bundle(catalog, indexed, lambda f: f['name'].encode(), root/'source')
            apk = root/'app.apk'
            with ZipFile(apk, 'w') as z:
                for file in (root/'source').iterdir(): z.write(file, PREFIX+file.name)
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            count, _ = extract(apk, catalog, root/'out', expected_hash=digest)
            self.assertEqual(count, len(indexed))
            with self.assertRaises(ValueError): extract(apk, catalog, root/'wrong', expected_hash='0'*64)
            catalog['notes'][0]['short'] = 'changed'
            with self.assertRaises(ValueError): extract(apk, catalog, root/'stale', expected_hash=digest)
            self.assertFalse((root/'stale').exists())

if __name__ == '__main__': unittest.main()
