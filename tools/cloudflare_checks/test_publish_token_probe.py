import io
import json
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError

import publish_token_probe as probe


class Response:
    status = 200

    def __init__(self, content):
        self.content = content

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size=-1):
        return self.content[:size]


def catalog(quizzes=None, revision=7):
    return json.dumps({"schemaVersion": 1, "revision": revision,
                       "quizzes": [] if quizzes is None else quizzes}).encode()


def error(code, content=b''):
    return HTTPError("https://example.invalid", code, "error", {}, io.BytesIO(content))


class TokenProbeTest(unittest.TestCase):
    def test_success_uses_only_fixed_host_and_missing_date(self):
        opener = Mock()
        opener.open.side_effect = [
            Response(catalog()), error(404, b'{"error":"Quiztag nicht im Katalog."}')
        ]
        self.assertEqual(probe.check("t" * 40, opener), "Token akzeptiert; Prüftag nicht vorhanden.")
        get, post = (call.args[0] for call in opener.open.call_args_list)
        self.assertTrue(get.full_url.startswith(probe.BASE + "/content?probe="))
        self.assertEqual(post.full_url, probe.BASE + "/admin/quiz-archive/" + probe.DAY)
        self.assertEqual(post.get_method(), "POST")
        self.assertEqual(post.get_header("If-match"), "7")

    def test_missing_token_fails_without_requests(self):
        opener = Mock()
        self.assertNotEqual(probe.check("", opener), "Token akzeptiert; Prüftag nicht vorhanden.")
        opener.open.assert_not_called()

    def test_existing_day_prevents_any_post(self):
        opener = Mock()
        opener.open.return_value = Response(catalog([{"published": probe.DAY}]))
        self.assertIn("kein Token-Test", probe.check("t" * 40, opener))
        self.assertEqual(opener.open.call_count, 1)

    def test_revision_and_schema_guards(self):
        for data in (catalog(revision=True), b'{"revision":7,"quizzes":[{}]}'):
            opener = Mock()
            opener.open.return_value = Response(data)
            self.assertIn("kein Token-Test", probe.check("t" * 40, opener))
            self.assertEqual(opener.open.call_count, 1)

    def test_404_from_proxy_is_not_accepted(self):
        opener = Mock()
        opener.open.side_effect = [Response(catalog()), error(404, b'not found')]
        self.assertIn("nicht bestätigt", probe.check("t" * 40, opener))

    def test_invalid_token_and_conflict(self):
        for code, phrase in ((401, "nicht akzeptiert"), (409, "Katalogrevision geändert")):
            opener = Mock()
            opener.open.side_effect = [Response(catalog()), error(code)]
            self.assertIn(phrase, probe.check("t" * 40, opener))

    def test_redirects_disabled(self):
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid'))


if __name__ == '__main__':
    unittest.main()
