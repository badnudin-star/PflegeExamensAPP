import datetime as dt
import unittest

import quiz_pipeline as qp


class EvidencePassageTests(unittest.TestCase):
    def test_segments_are_contiguous_exact_excerpts_covering_source(self):
        source = '  '.join(f'Wort{i}' for i in range(200)) + '\nEnde!'
        source = source.replace('Wort42', 'Äußerung,')
        expected = qp.normalize(source)
        passages = qp.evidence_passages(source)
        self.assertGreater(len(passages), 1)
        self.assertTrue(all(30 <= len(p) <= 500 and p in expected for p in passages))
        self.assertTrue(passages[0].startswith('Wort0 '))
        self.assertTrue(passages[-1].endswith('Ende!'))
        for i in range(200):
            if i != 42:
                self.assertTrue(any(f'Wort{i}' in p.split() for p in passages))
        self.assertTrue(any('Äußerung,' in p for p in passages))

    def test_exact_passage_is_attached_by_one_based_number_not_model_text(self):
        source = ' '.join(f'Wort{i}' for i in range(180))
        excerpts = qp.evidence_passages(source)
        items = [dict(question=f'Frage {i}', options=['A', 'B', 'C', 'D'], correctIndex=0,
                      explanation='Erklärung', evidenceId=2, evidence='erfundener Text') for i in range(10)]
        output = qp.validate_generated(items, 'Thema', 'beispiel.pdf', source, dt.date(2026, 11, 13).isoformat(), 0)
        self.assertTrue(all(q['sources'] == ['Lernzettel: beispiel.pdf', 'Beleg: ' + excerpts[1]] for q in output))
        self.assertTrue(all('erfundener Text' not in str(q) for q in output))
        self.assertIn(excerpts[1], qp.normalize(source))

    def test_invalid_ids_and_missing_source_abort(self):
        source = ' '.join(f'Wort{i}' for i in range(100))
        items = [dict(question=f'Frage {i}', options=['A', 'B', 'C', 'D'], correctIndex=0,
                      explanation='Erklärung', evidenceId=1) for i in range(10)]
        for bad in (0, -1, True, 1.0, '1', None, len(qp.evidence_passages(source)) + 1):
            items[0]['evidenceId'] = bad
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'Quellenabschnittsnummer'):
                qp.validate_generated(items, 'Thema', 'x.pdf', source, '2026-11-13', 0)
        with self.assertRaises(ValueError):
            qp.evidence_passages('kurz')
        with self.assertRaises(ValueError):
            qp.evidence_passages('x' * 501)

    def test_short_fragment_between_very_long_words_fails_without_looping(self):
        with self.assertRaisesRegex(ValueError, 'Ungültiger Quellabschnitt'):
            qp.evidence_passages('a' * 499 + ' x ' + 'b' * 499)

    def test_trailing_short_words_still_get_valid_contiguous_excerpt(self):
        source = ('a' * 470) + ' foo bar'
        passages = qp.evidence_passages(source)
        self.assertTrue(all(30 <= len(p) <= 500 and p in source for p in passages))
        self.assertTrue(passages[-1].endswith('foo bar'))


if __name__ == '__main__':
    unittest.main()
