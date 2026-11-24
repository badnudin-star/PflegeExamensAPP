import contextlib
import hashlib
import importlib.util
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("race_test", Path(__file__).with_name("ranking_synthetic_race_test.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeClient:
    def __init__(self, timeout=False):
        self.lock = threading.Lock()
        self.accounts = {}
        self.deleted = set()
        self.timeout = timeout

    def call(self, method, path, token=None, body=None):
        with self.lock:
            def response(status, value=None, headers=None):
                return status, json.dumps(value or {}).encode(), headers or {}
            if path == "/quiz/today":
                return response(200, {"date": "2026-11-13", "fingerprint": "abc", "questions": [
                    {"id": f"synthetic-ranking-{i}", "correctIndex": i % 4} for i in range(50)]})
            if path == "/participants" and method == "POST":
                if token in self.deleted:
                    return response(410)
                if self.timeout:
                    raise module.RequestFailure("simulated timeout")
                self.accounts[token] = {"state": "new", "username": body["username"]}
                return response(201)
            if path == "/participants/me" and method == "DELETE":
                if token not in self.accounts and token not in self.deleted:
                    return response(401)
                self.accounts.pop(token, None)
                self.deleted.add(token)
                return response(204, headers={"X-Quiz-Deletion-Receipt": "v1"})
            if token not in self.accounts:
                return response(401)
            account = self.accounts[token]
            if path == "/quiz/attempt" and method == "POST":
                if account["state"] == "closed":
                    return response(409)
                account["state"] = "recorded"
                payload = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode()
                account["hash"] = hashlib.sha256(payload).hexdigest()
                return response(201, {"correct": 50, "total": 50})
            if path == "/quiz/attempt/close":
                if account["state"] == "recorded":
                    return response(200, {"status": "recorded", "payloadHash": account["hash"]})
                account["state"] = "closed"
                return response(200, {"status": "closed"})
            if path.startswith("/quiz/attempt/result?"):
                if account["state"] == "closed":
                    return response(410, {"status": "closed"})
                return response(200, {"correct": 50, "total": 50, "payloadHash": account["hash"]})
            raise AssertionError("unexpected request")


class RaceScriptTests(unittest.TestCase):
    def test_success_and_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "recovery.json"
            client = FakeClient()
            self.assertTrue(module.run(client, path))
            self.assertEqual(client.accounts, {})
            self.assertEqual(len(client.deleted), 2)
            self.assertFalse(path.exists())

    def test_registration_timeout_preserves_recovery(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()) as out:
            path = Path(tmp) / "recovery.json"
            self.assertFalse(module.run(FakeClient(timeout=True), path))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            accounts = json.loads(path.read_text())["accounts"]
            self.assertTrue(all(a["token"] not in out.getvalue() for a in accounts))


if __name__ == "__main__":
    unittest.main()
