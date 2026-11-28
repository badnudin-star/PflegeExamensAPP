import unittest
from audit_practice import summarize

class PracticeAuditTest(unittest.TestCase):
    def test_reports_counts_without_content(self):
        base = {'cards':[{'id':'a','back':'secret'}], 'cases':[{'id':'b'}]}
        candidate = {'cards':[{'id':'a','back':'changed'}, {'id':'c'}], 'cases':[{'id':'b'}]}
        self.assertEqual(summarize(base, candidate), 'Karten: 2 insgesamt, 1 neu, 1 geändert; Fälle: 1 insgesamt, 0 neu, 0 geändert')
    def test_explicit_card_retirement_is_counted_but_cases_still_protected(self):
        base = {'cards':[{'id':'old'}], 'cases':[{'id':'case'}]}
        candidate = {'cards':[{'id':'new'}], 'cases':[{'id':'case'}], 'retiredCardIds':['old']}
        self.assertEqual(summarize(base, candidate), 'Karten: 1 insgesamt, 1 neu, 0 geändert, 1 stillgelegt; Fälle: 1 insgesamt, 0 neu, 0 geändert')
        with self.assertRaises(ValueError):
            summarize(base, dict(candidate, cases=[], retiredCardIds=['old', 'case']))
    def test_missing_id_is_rejected(self):
        with self.assertRaises(ValueError):
            summarize({'cards':[{'id':'a'}], 'cases':[]}, {'cards':[], 'cases':[]})

if __name__ == '__main__': unittest.main()
