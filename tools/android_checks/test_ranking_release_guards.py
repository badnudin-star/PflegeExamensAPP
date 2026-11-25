from pathlib import Path
import contextlib
import io
import tempfile
import unittest
from compare_release_certificates import compare
from verify_ranking_buildconfig import URL, verify

class RankingReleaseGuardTests(unittest.TestCase):
    def test_workflow_requires_manual_main_and_prior_signature(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/internal-ranking-test-release.yml').read_text()
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertNotIn('  push:', workflow)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirm == true", workflow)
        self.assertIn('environment: internal-release', workflow)
        self.assertIn('needs: preflight', workflow)
        self.assertIn('-PrankingUrl=' + URL + ' -PenableRankedParticipation=true', workflow)
        self.assertIn('compare_release_certificates.py', workflow)
        self.assertIn('verify_ranking_buildconfig.py', workflow)
        self.assertIn('actions: read', workflow)
        self.assertIn('if: always()', workflow)
    def test_matching_unique_certificate_only(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp)/'a', Path(temp)/'b'
            cert = 'Signer #1 certificate SHA-256 digest: ' + 'a'*64 + '\n'
            a.write_text(cert)
            # Hex is case-insensitive; the report labels remain exact.
            b.write_text('Signer #1 certificate SHA-256 digest: ' + 'A'*64 + '\n')
            with contextlib.redirect_stdout(io.StringIO()): compare(a, b)
            b.write_text('Signer #1 certificate SHA-256 digest: ' + 'b'*64 + '\n')
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError): compare(a, b)
            b.write_text(cert + 'Signer #2 certificate SHA-256 digest: ' + 'a'*64 + '\n')
            with self.assertRaises(ValueError): compare(a, b)
            b.write_text('not a report')
            with self.assertRaises(ValueError): compare(a, b)
    def test_buildconfig_must_enable_exact_public_endpoint(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'BuildConfig.java'
            text = 'public static final boolean ENABLE_RANKED_PARTICIPATION = true;\npublic static final String RANKING_URL = "' + URL + '";\n'
            path.write_text(text); verify(path)
            path.write_text(text.replace('= true;', '= false;'))
            with self.assertRaises(ValueError): verify(path)
            path.write_text(text.replace(URL, 'https://example.org/leaderboard'))
            with self.assertRaises(ValueError): verify(path)

if __name__ == '__main__': unittest.main()
