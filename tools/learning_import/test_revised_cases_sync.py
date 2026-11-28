import unittest
from unittest.mock import patch
from sync_practice import inventory, import_practice, retire_replaced_cases


def revised_case(day, number, extra=0):
    return {'id': 'case-v2-' + format(day * 10000 + number + extra, '064x'), 'examDay': day,
            'title': 'Test', 'text': 'Synthetisch', 'published': '2026-01-01', 'sources':['Q']}


def legacy():
    return [{'id': f'case-day-{day}-{number:02d}'} for day in range(1, 4) for number in range(1, 11)]


def revised():
    return [revised_case(day, number) for day in range(1, 4) for number in range(1, 11)]


def files():
    return [{'id': f'{day}-{number}', 'name': f'Tag_{day}_Fall_{number:02d}_Test.pdf',
             'mimeType': 'application/pdf', 'modifiedTime': '2026-01-01T00:00:00Z'}
            for day in range(1, 4) for number in range(1, 11)]


FOLDER = {'id': 'child', 'name': 'Fallbeispiele mit Zeilennummern – Arbeitsstand',
          'mimeType': 'application/vnd.google-apps.folder'}


class RevisedCaseSyncTest(unittest.TestCase):
    def test_exact_legacy_migration(self):
        self.assertEqual(set(retire_replaced_cases({'cases': legacy()}, revised())), {c['id'] for c in legacy()})
        with self.assertRaises(ValueError): retire_replaced_cases({'cases': legacy()[:-1]}, revised())
        with self.assertRaises(ValueError): retire_replaced_cases({'cases': legacy()}, revised()[:-1])

    def test_updates_retire_without_resurrection_or_mass_removal(self):
        old = revised()
        new = old.copy(); new[0] = revised_case(1, 1, 1000)
        retired = retire_replaced_cases({'cases': old}, new)
        self.assertEqual(retired, [old[0]['id']])
        with self.assertRaises(ValueError): retire_replaced_cases({'cases': new, 'retiredCaseIds': retired}, old)
        massive = [revised_case(i['examDay'], j + 1, 5000) if j < 4 else i for j, i in enumerate(old)]
        with self.assertRaisesRegex(ValueError, 'Zu viele'): retire_replaced_cases({'cases': old}, massive)

    def test_inventory_ignores_labelled_altstand_but_rejects_ambiguity(self):
        archive = {'id': 'alt', 'name': 'ALTSTAND_Tag_3_Fall_05.pdf', 'mimeType': 'application/pdf'}
        with patch('sync_practice.list_children', side_effect=[[FOLDER], files() + [archive]]):
            self.assertEqual(len(inventory(object(), 'parent', 'cases-v2')['files']), 30)
        duplicate = files(); duplicate[0] = dict(duplicate[1], id='other')
        with patch('sync_practice.list_children', side_effect=[[FOLDER], duplicate]):
            with self.assertRaises(ValueError): inventory(object(), 'parent', 'cases-v2')
        with patch('sync_practice.list_children', return_value=[FOLDER, FOLDER]):
            with self.assertRaises(ValueError): inventory(object(), 'parent', 'cases-v2')

    def test_import_preserves_other_sections_and_rechecks_source(self):
        base = {'cases': legacy(), 'cards': [{'id': 'c'}], 'notes': [{'topic': 'x'}], 'quizzes': [{'id': 'q'}]}
        snap = {'folder': FOLDER, 'files': files()}
        iterator = iter(revised())
        with patch('sync_practice.inventory', return_value=snap), patch('sync_practice.download', return_value=b'x'), \
             patch('sync_practice.extract_pdf', return_value='text'), \
             patch('sync_practice.parse_revised_case', side_effect=lambda *args: next(iterator)):
            result = import_practice(base, object(), cases_folder='folder', case_format='revised')
        self.assertEqual(len(result['cases']), 30)
        self.assertEqual(set(result['retiredCaseIds']), {c['id'] for c in legacy()})
        for key in ('notes', 'cards', 'quizzes'): self.assertEqual(result[key], base[key])


if __name__ == '__main__': unittest.main()
