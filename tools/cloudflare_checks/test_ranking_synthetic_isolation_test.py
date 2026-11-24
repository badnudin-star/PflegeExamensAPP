import contextlib
import importlib.util
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("ranking_test", Path(__file__).with_name("ranking_synthetic_isolation_test.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeClient:
    def __init__(self, registration_timeout=False):
        self.lock = threading.Lock()
        self.accounts = {}
        self.registration_timeout = registration_timeout

    def call(self, method, path, token=None, body=None):
        with self.lock:
            def response(status, value=None, headers=None):
                return status, json.dumps(value or {}).encode(), headers or {}
            if path == "/quiz/today":
                return response(200, {"date": "2026-11-13", "fingerprint": "abc", "questions": [
                    {"id": f"synthetic-ranking-{i}", "correctIndex": i % 4} for i in range(50)]})
            if path == "/participants" and method == "POST":
                if self.registration_timeout:
                    raise module.RequestFailure("simulated timeout")
                self.accounts[token] = {"username": body["username"], "submitted": False}
                return response(201)
            if path == "/participants/me" and method == "DELETE":
                if token not in self.accounts:
                    return response(401)
                del self.accounts[token]
                return response(204, headers={"X-Quiz-Deletion-Receipt": "v1"})
            if path == "/participants/me" and method == "GET":
                return response(200, {"username": self.accounts[token]["username"]})
            if path == "/quiz/attempt" and method == "POST":
                if self.accounts[token]["submitted"]:
                    return response(409)
                self.accounts[token]["submitted"] = True
                return response(201, {"correct": 50, "total": 50})
            if path.startswith("/quiz/attempt/result?"):
                if not self.accounts[token]["submitted"]:
                    return response(404, {"error": "Noch kein gewerteter Versuch."})
                return response(200, {"correct": 50, "total": 50})
            raise AssertionError("unexpected request")


class IsolationScriptTests(unittest.TestCase):
    def test_success_deletes_accounts_and_recovery_file(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "recovery.json"
            client = FakeClient()
            self.assertTrue(module.run(client, path))
            self.assertFalse(path.exists())
            self.assertEqual(client.accounts, {})

    def test_ambiguous_registration_retains_protected_recovery_file(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()) as output:
            path = Path(tmp) / "recovery.json"
            self.assertFalse(module.run(FakeClient(registration_timeout=True), path))
            self.assertTrue(path.exists())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            secrets = json.loads(path.read_text())["accounts"]
            self.assertTrue(all(item["token"] not in output.getvalue() for item in secrets))

    def test_existing_recovery_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "recovery.json"
            path.write_text("keep")
            with self.assertRaises(module.RequestFailure):
                module.run(FakeClient(), path)
            self.assertEqual(path.read_text(), "keep")


if __name__ == "__main__":
    unittest.main()
