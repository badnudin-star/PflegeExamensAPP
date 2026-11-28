import io
import unittest

from openpyxl import Workbook

import import_revised_cards as revised
from import_notes import TOPICS


def make_workbook():
    book = Workbook()
    overview = book.active
    overview.title = 'Übersicht'
    overview.append(['KARTEIKARTEN · PFLEGEEXAMEN'])
    overview.append(list(revised.OVERVIEW_HEADERS))
    cards = book.create_sheet('Alle Karten')
    cards.append(list(revised.HEADERS))
    for number, (name, slug, _) in enumerate(revised.TOPIC_MAP, 1):
        source = slug + '_Neu.pdf'
        overview.append([number, name, 1, source])
        cards.append([f'{number:02d}.01', name, f'Frage {number}?', f'Antwort {number}.', source])
    return book


def parse(book):
    data = io.BytesIO()
    book.save(data)
    data.seek(0)
    return revised.read_revised_cards(data)


class RevisedCardsTest(unittest.TestCase):
    def test_all_topics_explicitly_mapped_and_stable(self):
        book = make_workbook()
        cards = parse(book)
        self.assertEqual(len(cards), 42)
        self.assertEqual({c['topic'] for c in cards}, {topic[1] for topic in TOPICS})
        self.assertEqual(cards[2]['topic'], TOPICS[14][1])  # Workbook #3 is not app topic #3.
        self.assertEqual(cards[0]['id'], parse(book)[0]['id'])
        self.assertTrue(cards[0]['id'].startswith('card-v2-'))
        self.assertIn('Altersgerechte_Kommunikation_Neu.pdf', cards[0]['sources'])

    def test_edited_question_or_answer_gets_new_id(self):
        book = make_workbook()
        previous = parse(book)[0]['id']
        book['Alle Karten']['D2'] = 'Andere Antwort.'
        self.assertNotEqual(previous, parse(book)[0]['id'])
        book['Alle Karten']['D2'] = 'Antwort 1.'
        book['Alle Karten']['C2'] = 'Andere Frage?'
        self.assertNotEqual(previous, parse(book)[0]['id'])

    def test_bad_mappings_counts_formulas_and_missing_data_fail_closed(self):
        mutations = [
            lambda b: setattr(b['Übersicht']['B3'], 'value', 'Falsches Thema'),
            lambda b: setattr(b['Übersicht']['C3'], 'value', 2),
            lambda b: setattr(b['Übersicht']['D3'], 'value', 'Falsche_Quelle.pdf'),
            lambda b: setattr(b['Alle Karten']['A2'], 'value', '01.02'),
            lambda b: setattr(b['Alle Karten']['B2'], 'value', 'Falsches Thema'),
            lambda b: setattr(b['Alle Karten']['C2'], 'value', '=1+1'),
            lambda b: setattr(b['Alle Karten']['D2'], 'value', None),
            lambda b: setattr(b['Alle Karten']['E2'], 'value', 'Falsche_Quelle.pdf'),
            lambda b: setattr(b['Alle Karten']['F2'], 'value', 'Überraschung'),
            lambda b: b.remove(b['Alle Karten']),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                book = make_workbook()
                mutate(book)
                with self.assertRaises(ValueError):
                    parse(book)

    def test_duplicate_card_content_is_rejected(self):
        book = make_workbook()
        sheet = book['Alle Karten']
        book['Übersicht']['C3'] = 2
        sheet.append(['01.02', revised.TOPIC_MAP[0][0], 'Frage 1?', 'Antwort 1.',
                      revised.TOPIC_MAP[0][1] + '_Neu.pdf'])
        with self.assertRaisesRegex(ValueError, 'Doppelte'):
            parse(book)

    def test_addition_does_not_change_existing_ids(self):
        book = make_workbook()
        old = parse(book)
        book['Übersicht']['C3'] = 2
        book['Alle Karten'].append(['01.02', revised.TOPIC_MAP[0][0], 'Zusatz?', 'Zusatz.',
                                     revised.TOPIC_MAP[0][1] + '_Neu.pdf'])
        new = parse(book)
        self.assertTrue({c['id'] for c in old}.issubset({c['id'] for c in new}))
        self.assertEqual(len(new), 43)


if __name__ == '__main__':
    unittest.main()
