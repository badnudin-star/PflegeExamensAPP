import unittest
from check_release_request import validate


class ReleaseRequestTests(unittest.TestCase):
    def test_requires_exact_main_and_explicit_confirmation(self):
        validate('refs/heads/main', 'true')
        for ref, confirmation in [('refs/heads/main', 'false'), ('refs/heads/main', ''),
                                  ('refs/heads/other', 'true'), ('refs/tags/main', 'true')]:
            with self.subTest(ref=ref, confirmation=confirmation), self.assertRaises(ValueError):
                validate(ref, confirmation)


if __name__ == '__main__': unittest.main()
