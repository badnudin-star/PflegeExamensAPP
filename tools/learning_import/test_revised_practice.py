import copy
import unittest
from unittest.mock import patch

from import_notes import TOPICS
from sync_practice import import_practice, retire_replaced_cards


def card(number, topic):
    return {'id': 'card-v2-' + format(number, '064x'), 'topic': topic,
            'front': 'Frage', 'back': 'Antwort', 'sources': ['Quelle']}


def v2_cards():
    return [card(i + 1, topic) for i, (_, topic) in enumerate(TOPICS)]


def legacy_cards():
    result = []
    for i in range(523):
        result.append({'id': f'card-{(i % 42) + 1:02d}-{(i // 42) + 1:03d}',
                       'topic': TOPICS[i % 42][1], 'front': 'Alt', 'back': 'Antwort', 'sources': ['Q']})
    return result


class RevisedPracticeTests(unittest.TestCase):
    def setUp(self):
        self.cards = v2_cards()
        self.base = {'cards': self.cards, 'cases': [], 'notes': [], 'quizzes': []}

    def test_confirmed_migration_only_from_exact_audited_legacy_shape(self):
        legacy = legacy_cards()
        new = [card(i + 1, TOPICS[i % 42][1]) for i in range(481)]
        self.assertEqual(set(retire_replaced_cards({'cards': legacy}, new)), {c['id'] for c in legacy})
        for base, candidate in (({'cards': legacy[:-1]}, new), ({'cards': legacy}, new[:-1]),
                                ({'cards': []}, new), ({'cards': legacy, 'retiredCardIds': ['x']}, new)):
            with self.assertRaises(ValueError): retire_replaced_cards(base, candidate)

    def test_small_change_retires_and_cannot_reuse_old_id(self):
        old = self.cards[0]
        changed = card(123456, old['topic'])
        replacement = [changed] + self.cards[1:]
        retired = retire_replaced_cards(self.base, replacement)
        self.assertEqual(retired, [old['id']])
        next_base = {'cards': replacement, 'retiredCardIds': retired}
        with self.assertRaisesRegex(ValueError, 'erneut verwendet'):
            retire_replaced_cards(next_base, self.cards)
        self.assertEqual(retire_replaced_cards(next_base, replacement), retired)

    def test_partial_mass_removal_and_unknown_generation_rejected(self):
        with self.assertRaises(ValueError): retire_replaced_cards(self.base, self.cards[1:])  # missing topic
        many = [card(i + 1000, c['topic']) if i < 5 else c for i, c in enumerate(self.cards)]
        with self.assertRaisesRegex(ValueError, 'Zu viele'): retire_replaced_cards(self.base, many)
        bad = copy.deepcopy(self.base); bad['cards'][0]['id'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'Generation'): retire_replaced_cards(bad, self.cards)

    def test_import_keeps_other_sections_and_rechecks_snapshot(self):
        replacement = [card(1000, self.cards[0]['topic'])] + self.cards[1:]
        with patch('sync_practice.inventory', return_value=[{'name': 'book'}]), \
             patch('sync_practice.download', return_value=b'x'), patch('sync_practice.workbook'), \
             patch('sync_practice.read_revised_cards', return_value=replacement):
            result = import_practice(self.base, object(), 'folder', card_format='revised')
        self.assertEqual(result['cards'], replacement)
        self.assertEqual(result['retiredCardIds'], [self.cards[0]['id']])
        for key in ('cases', 'notes', 'quizzes'): self.assertEqual(result[key], self.base[key])
        with patch('sync_practice.inventory', side_effect=[[{'name': 'book'}], []]), \
             patch('sync_practice.download', return_value=b'x'), patch('sync_practice.workbook'), \
             patch('sync_practice.read_revised_cards', return_value=replacement):
            with self.assertRaisesRegex(ValueError, 'geändert'):
                import_practice(self.base, object(), 'folder', card_format='revised')


if __name__ == '__main__': unittest.main()
