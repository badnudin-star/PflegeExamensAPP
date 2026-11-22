import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import bundle_published
from import_notes import MAX_CATALOG, TOPICS
from bundle_published import validate_endpoint, validate_snapshot


def catalog(revision=3):
    return {
        'schemaVersion': 1, 'revision': revision,
        'notes': [{'topic': title, 'short': 'Einfach', 'balanced': 'Neu', 'detailed': 'Neu',
                   'formatVersion': 2, 'sources': ['PDF']} for _, title in TOPICS],
        'cards': [], 'cases': [], 'quizzes': [],
    }


def encode(c):
    return json.dumps(c, ensure_ascii=False).encode()


class OfflineBundleTests(unittest.TestCase):
    def test_valid_full_snapshot(self):
        baseline, updated = catalog(3), catalog(4)
        self.assertEqual(validate_snapshot(encode(updated), baseline), updated)
        self.assertEqual(validate_snapshot(encode(baseline), baseline), baseline)

    def test_rejects_outdated_conflicting_or_incomplete_snapshots(self):
        baseline = catalog(3)
        older = catalog(2)
        equal_changed = catalog(3); equal_changed['notes'][0]['short'] = 'other'
        partial = catalog(4); partial['notes'].pop()
        old_format = catalog(4); old_format['notes'][0]['formatVersion'] = 1
        blank = catalog(4); blank['notes'][0]['short'] = ''
        duplicate = catalog(4); duplicate['notes'].append(duplicate['notes'][0])
        for value in [older, equal_changed, partial, old_format, blank, duplicate]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_snapshot(encode(value), baseline)
        with self.assertRaises(ValueError): validate_snapshot(b'{' , baseline)
        with self.assertRaises(ValueError): validate_snapshot(b'x' * (MAX_CATALOG + 1), baseline)

    def test_bundle_reports_exact_downloaded_bytes_only_after_validation(self):
        baseline, update = catalog(3), catalog(4)
        with tempfile.TemporaryDirectory() as temp:
            asset = Path(temp) / 'learning.json'
            asset.write_bytes(encode(baseline))
            raw = encode(update)
            with patch.object(bundle_published, 'download', return_value=raw), \
                 patch.object(bundle_published, 'report_capacity') as report, \
                 patch('sys.argv', ['bundle_published.py', '--url', 'https://example.org/content', '--asset', str(asset)]):
                bundle_published.main()
                report.assert_called_once_with(len(raw), 'bundle')
            self.assertEqual(asset.read_bytes(), raw)
            bad = encode(dict(update, revision=2))
            with patch.object(bundle_published, 'download', return_value=bad), \
                 patch.object(bundle_published, 'report_capacity') as report, \
                 patch('sys.argv', ['bundle_published.py', '--url', 'https://example.org/content', '--asset', str(asset)]):
                with self.assertRaises(ValueError): bundle_published.main()
                report.assert_not_called()
            self.assertEqual(asset.read_bytes(), raw)

    def test_rejects_unsafe_urls(self):
        self.assertEqual(validate_endpoint('https://example.org/content'), 'https://example.org/content')
        for url in ('http://example.org/content', 'https://user:password@example.org/content',
                    'https://example.org/', 'https://example.org/content?q=1', 'https://example.org/content#x'):
            with self.subTest(url=url), self.assertRaises(ValueError): validate_endpoint(url)


if __name__ == '__main__':
    unittest.main()
