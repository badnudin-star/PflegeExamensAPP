import copy
from collections import Counter
import random
import unittest

import quiz_pipeline as qp
import test_quiz_pipeline


class AnswerPositionTests(unittest.TestCase):
    def draft(self):
        return test_quiz_pipeline.QuizTests().draft()

    def test_distribution_correct_answer_and_other_fields_preserved(self):
        original = self.draft()
        untouched = copy.deepcopy(original)
        for seed in range(20):
            balanced = qp.balance_answer_positions(original, random.Random(seed))
            counts = Counter(q['correctIndex'] for q in balanced['quizzes'])
            self.assertEqual(sorted(counts.values()), [12, 12, 13, 13])
            for old, new in zip(original['quizzes'], balanced['quizzes']):
                self.assertEqual(old['options'][old['correctIndex']], new['options'][new['correctIndex']])
                self.assertCountEqual(old['options'], new['options'])
                for key in ('id', 'published', 'topic', 'question', 'explanation', 'sources'):
                    self.assertEqual(old[key], new[key])
            qp.validate_draft(balanced)
        self.assertEqual(original, untouched)

    def test_rejects_common_position_dependent_forms_without_changing_input(self):
        for field, value in [
            ('question', 'Welche der oben genannten Antworten ist richtig?'),
            ('explanation', 'Antwort B ist korrekt.'),
            ('explanation', 'Die Antwort ist 2.'),
            ('question', 'Welche Option A ist richtig?'),
            ('question', 'Sind A und B richtig?'),
            ('question', 'Ist die erste Antwort korrekt?'),
            ('option', 'Alle genannten Antworten sind korrekt'),
            ('option', 'B) Ein Beispiel'),
        ]:
            draft = self.draft()
            if field == 'option': draft['quizzes'][0]['options'][0] = value
            else: draft['quizzes'][0][field] = value
            untouched = copy.deepcopy(draft)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'Positionsabhängige'):
                qp.balance_answer_positions(draft, random.Random(42))
            self.assertEqual(draft, untouched)

    def test_invalid_draft_is_not_shuffled(self):
        draft = self.draft()
        draft['quizzes'][0]['correctIndex'] = 9
        with self.assertRaises(ValueError):
            qp.balance_answer_positions(draft, random.Random(0))


if __name__ == '__main__':
    unittest.main()
