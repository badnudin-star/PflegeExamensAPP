"""Static checks for backup XML. Not a substitute for real Android restore testing."""
from pathlib import Path
import unittest
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res/xml'
ANDROID = '{http://schemas.android.com/apk/res/android}'
SENSITIVE_PREFS = {'pflege_examen_prefs.xml', 'pflege_examen_ranking_prefs.xml'}
PROGRESS_PREFS = {'card_progress.xml', 'case_progress.xml', 'quiz_practice.xml'}


class BackupRulesTests(unittest.TestCase):
    def test_manifest_enables_both_rules(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        app = manifest.find('application')
        self.assertEqual(app.get(ANDROID + 'allowBackup'), 'true')
        self.assertEqual(app.get(ANDROID + 'fullBackupContent'), '@xml/backup_rules')
        self.assertEqual(app.get(ANDROID + 'dataExtractionRules'), '@xml/data_extraction_rules')

    def check_excludes(self, root):
        # Denylists keep ordinary progress eligible; they cannot guarantee backup or restore.
        self.assertFalse(root.findall('include'))
        excludes = {(x.get('domain'), x.get('path')) for x in root.findall('exclude')}
        self.assertEqual(excludes, {('sharedpref', name) for name in SENSITIVE_PREFS})
        self.assertTrue(all(('sharedpref', name) not in excludes for name in PROGRESS_PREFS))

    def test_legacy_and_new_backup_and_transfer_exclude_sensitive_preferences(self):
        old = ET.parse(RES / 'backup_rules.xml').getroot()
        self.assertEqual(old.tag, 'full-backup-content')
        self.check_excludes(old)
        new = ET.parse(RES / 'data_extraction_rules.xml').getroot()
        self.assertEqual(new.tag, 'data-extraction-rules')
        for section in ('cloud-backup', 'device-transfer'):
            with self.subTest(section=section):
                child = new.find(section)
                self.assertIsNotNone(child)
                self.check_excludes(child)

    def test_exclusions_match_actual_legacy_preferences(self):
        app = ROOT / 'app/src/main/java/de/pflegeexamen/app/data'
        for path, preference in [('ai/AiPreferencesStore.kt','pflege_examen_prefs'),
                                 ('ranking/RankingPreferencesStore.kt','pflege_examen_ranking_prefs')]:
            self.assertIn('getSharedPreferences("' + preference + '"', (app/path).read_text())

    def test_literal_preference_names_are_reviewed_when_the_inventory_changes(self):
        # The Android rules are a denylist: a newly added preference is eligible by
        # default. Force a privacy/backup review for literal names in app code.
        source = ROOT / 'app/src/main/java'
        found = set()
        for path in source.rglob('*.kt'):
            found.update(re.findall(r'getSharedPreferences\s*\(\s*"([^"\n]+)"', path.read_text()))
        self.assertEqual(found, {name.removesuffix('.xml') for name in SENSITIVE_PREFS | PROGRESS_PREFS})

    def test_ranking_sensitive_files_remain_in_no_backup_directory(self):
        quiz = ROOT / 'app/src/main/java/de/pflegeexamen/app/data/quiz'
        for path, filename in [
            ('AndroidRankingTokenVault.kt', 'quiz-ranking-token.bin'),
            ('AndroidPendingQuizAttempt.kt', 'quiz-ranking-pending.json'),
            ('AndroidRankingDeletion.kt', 'quiz-ranking-deletion.json'),
        ]:
            with self.subTest(path=path):
                self.assertIn(f'File(context.noBackupFilesDir, "{filename}")', (quiz / path).read_text())


if __name__ == '__main__': unittest.main()
