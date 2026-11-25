import unittest
from unittest.mock import patch

import audit_notes
from import_notes import TOPICS, topic_candidates


class AuditNotesTests(unittest.TestCase):
    def setUp(self):
        self.indexed = {name: {'name': name} for i in range(len(TOPICS)) for name in topic_candidates(i)[0]}
        self.catalog = {'notes': []}
        for i, (_, title) in enumerate(TOPICS):
            main, simple = topic_candidates(i)[0]
            self.catalog['notes'].append({'topic': title, 'formatVersion': 2, 'detailed': main,
                                          'balanced': main, 'short': simple,
                                          'sources': ['Lernzettel: ' + main, 'Lernzettel: ' + simple]})

    def run_audit(self):
        def extract(data): return data.decode()
        with patch.object(audit_notes, 'extract_pdf', side_effect=extract), \
             patch.object(audit_notes, 'inspect_pdf', return_value={'pages': 2, 'images': 1, 'image_pages': [1], 'empty_pages': [2]}):
            return audit_notes.audit(self.catalog, self.indexed, lambda f: f['name'].encode())

    def test_checks_all_mapped_sources_and_both_variants(self):
        result = self.run_audit()
        self.assertEqual(result['topics'], 42)
        self.assertEqual(result['mismatches'], [])
        self.assertEqual(result['image_files'], result['files'])
        self.assertEqual(result['empty_pages'], result['files'])
        self.assertEqual(result['findings'][0], (topic_candidates(0)[0][0], [1], [2]))
        self.catalog['notes'][0]['short'] = 'truncated'
        self.catalog['notes'][1]['balanced'] = 'different'
        self.assertEqual(self.run_audit()['mismatches'], [TOPICS[0][1], TOPICS[1][1]])

    def test_missing_topic_and_wrong_mapping_fail_or_are_reported(self):
        self.catalog['notes'].pop()
        with self.assertRaises(ValueError): self.run_audit()
        self.setUp()
        self.catalog['notes'][0]['sources'] = ['unrelated']
        self.assertEqual(self.run_audit()['mismatches'], [TOPICS[0][1]])

    def test_workflow_is_manual_read_only_and_does_not_publish(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/audit-learning-notes.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertNotIn('CONTENT_PUBLISH_TOKEN', workflow)
        self.assertNotIn('upload-artifact', workflow)


if __name__ == '__main__': unittest.main()
