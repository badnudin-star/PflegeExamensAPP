import unittest
from unittest.mock import patch

from audit_revised_cards import audit, FILENAME

FILE = {'id': 'file', 'name': FILENAME,
        'mimeType': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}


class AuditRevisedCardsTests(unittest.TestCase):
    def test_read_only_audit_checks_snapshot(self):
        cards = [{'topic': 'a'}, {'topic': 'b'}]
        with patch('audit_revised_cards.list_children', return_value=[FILE]) as listing, \
             patch('audit_revised_cards.download', return_value=b'data'), \
             patch('audit_revised_cards.workbook', return_value='workbook'), \
             patch('audit_revised_cards.read_revised_cards', return_value=cards):
            self.assertEqual(audit(object(), 'folder'), (2, 2))
            self.assertEqual(listing.call_count, 2)

    def test_missing_duplicate_changed_and_bad_folder_rejected(self):
        with self.assertRaises(ValueError):
            audit(object(), '../bad')
        for files in ([], [FILE, FILE], [dict(FILE, mimeType='text/plain')]):
            with self.subTest(files=files), patch('audit_revised_cards.list_children', return_value=files):
                with self.assertRaises(ValueError): audit(object(), 'folder')
        with patch('audit_revised_cards.list_children', side_effect=[[FILE], [dict(FILE, id='other')]]), \
             patch('audit_revised_cards.download', return_value=b'data'), \
             patch('audit_revised_cards.workbook'), patch('audit_revised_cards.read_revised_cards', return_value=[]):
            with self.assertRaisesRegex(ValueError, 'geändert'):
                audit(object(), 'folder')


if __name__ == '__main__': unittest.main()
