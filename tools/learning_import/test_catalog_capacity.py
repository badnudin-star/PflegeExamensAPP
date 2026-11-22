import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from catalog_capacity import capacity, report_capacity
from import_notes import MAX_CATALOG


class CatalogCapacityTests(unittest.TestCase):
    def test_exact_thresholds_and_integer_calculation(self):
        for size, expected in [(0, 'ok'), (799, 'ok'), (800, 'warning'), (949, 'warning'),
                               (950, 'critical'), (1000, 'critical'), (1001, 'over')]:
            with self.subTest(size=size):
                status, message = capacity(size, 1000)
                self.assertEqual(expected, status)
                self.assertIn(f'{size} / 1000 Bytes', message)
        self.assertIn('1 Bytes frei', capacity(999, 1000)[1])
        self.assertIn('1 Bytes über dem Limit', capacity(1001, 1000)[1])
        self.assertIn('79,9 %', capacity(799, 1000)[1])
        for invalid in (-1, True, 1.1, '1'):
            with self.assertRaises(ValueError): capacity(invalid, 1000)
        with self.assertRaises(ValueError): capacity(0, 0)

    def test_only_numeric_details_and_fixed_labels_go_to_action_logs_and_summary(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {
            'GITHUB_ACTIONS': 'true', 'GITHUB_STEP_SUMMARY': str(Path(temp) / 'summary')
        }, clear=True):
            output = io.StringIO()
            with contextlib.redirect_stdout(output): report_capacity(MAX_CATALOG * 95 // 100 + 1, 'quiz_candidate')
            text = output.getvalue()
            self.assertIn('Quiz-Katalogkandidat', text)
            self.assertIn('::warning title=Kataloggröße::', text)
            summary = (Path(temp) / 'summary').read_text()
            self.assertIn('Quiz-Katalogkandidat', summary)
            self.assertIn('Archivierung', summary)
            self.assertNotIn('Lernzettel: ', text + summary)
            self.assertNotIn('Beleg: ', text + summary)
            with self.assertRaises(ValueError): report_capacity(5, 'arbitrary user input')

    def test_reporting_summary_failure_does_not_block_existing_workflow(self):
        with patch.dict(os.environ, {'GITHUB_STEP_SUMMARY': '/nonexistent/summary'}, clear=True):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                report_capacity(42, 'server')
            self.assertIn('konnte nicht', output.getvalue())
            self.assertNotIn('::warning', output.getvalue())
