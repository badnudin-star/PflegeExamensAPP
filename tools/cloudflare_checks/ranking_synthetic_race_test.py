#!/usr/bin/env python3
"""Small, destructive-to-test-data-only test for the explicitly isolated worker.

Never use production hosts, real accounts, or real quiz content. The test leaves
pseudonymous deletion barriers in the test database. Keep the recovery file on an
uncertain outcome; never print its contents or send it to anyone.
"""
import concurrent.futures
import json
import os
import re
import secrets
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://pflegeexamen-ranking-test.badnudin.workers.dev"
KEY_FILE = Path.home() / ".pflege-ranking-test-key.txt"
RECOVERY_FILE = Path.home() / ".pflege-ranking-race-recovery.json"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class RequestFailure(Exception):
    pass


class Client:
    def __init__(self, key):
        self.key = key
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, token=None, body=None):
        headers = {"X-Pflege-Test-Key": self.key, "User-Agent": "Mozilla/5.0"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None
        if body is not None:
            data = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path, headers=headers, data=data, method=method)
        try:
            try:
                with self.opener.open(request, timeout=30) as response:
                    return response.status, response.read(200000), dict(response.headers)
            except urllib.error.HTTPError as error:
                return error.code, error.read(4096), dict(error.headers)
        except (OSError, ValueError) as error:
            raise RequestFailure("Verbindung fehlgeschlagen") from error


def require(condition, label):
    if not condition:
        raise RequestFailure(label)
    print(label + ": OK", flush=True)


def data(result):
    try:
        parsed = json.loads(result[1])
        if not isinstance(parsed, dict):
            raise ValueError
        return parsed
    except (ValueError, UnicodeError) as error:
        raise RequestFailure("Unerwartete Serverantwort") from error


def receipt(result):
    return result[0] == 204 and any(k.lower() == "x-quiz-deletion-receipt" and v == "v1" for k, v in result[2].items())


def save_recovery(path, accounts):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump({"purpose": "isolated-ranking-test", "accounts": accounts}, handle)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())


def run(client, recovery_path):
    response = client.call("GET", "/quiz/today")
    quiz = data(response)
    questions = quiz.get("questions")
    require(response[0] == 200 and isinstance(questions, list) and len(questions) == 50 and
            all(isinstance(q, dict) and q.get("id") == f"synthetic-ranking-{i}" and
                type(q.get("correctIndex")) is int and 0 <= q["correctIndex"] <= 3
                for i, q in enumerate(questions)) and
            isinstance(quiz.get("date"), str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", quiz["date"]) and
            isinstance(quiz.get("fingerprint"), str), "Künstliches Testquiz")
    accounts = [{"token": secrets.token_hex(32), "username": "Test_" + secrets.token_hex(6)} for _ in range(2)]
    try:
        save_recovery(recovery_path, accounts)
    except FileExistsError as error:
        raise RequestFailure("Sicherungsdatei existiert bereits; nicht überschreiben") from error
    except OSError as error:
        raise RequestFailure("Sicherungsdatei konnte nicht erstellt werden") from error
    states = ["not-started", "not-started"]
    test_ok = False
    try:
        for i, account in enumerate(accounts):
            states[i] = "uncertain"
            result = client.call("POST", "/participants", account["token"], {"username": account["username"]})
            require(result[0] == 201, f"Künstliches Konto {i+1} registriert")
            states[i] = "registered"
        submission = {"date": quiz["date"], "fingerprint": quiz["fingerprint"],
                      "answers": [{"id": q["id"], "option": q["correctIndex"]} for q in questions]}
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a = pool.submit(client.call, "POST", "/quiz/attempt", accounts[0]["token"], submission)
            b = pool.submit(client.call, "POST", "/quiz/attempt/close", accounts[0]["token"], {"date": quiz["date"]})
            attempt, closure = a.result(), b.result()
        closure_body = data(closure)
        path = "/quiz/attempt/result?date=" + quiz["date"]
        outcome = client.call("GET", path, accounts[0]["token"])
        print(f"Abgabe/Tagessperre HTTP: {attempt[0]}/{closure[0]}; Ergebnis: {outcome[0]}", flush=True)
        if closure[0] == 200 and closure_body.get("status") == "closed":
            consistent = attempt[0] == 409 and outcome[0] == 410 and data(outcome).get("status") == "closed"
        elif closure[0] == 200 and closure_body.get("status") == "recorded":
            saved = data(outcome)
            consistent = (attempt[0] == 201 and outcome[0] == 200 and saved.get("correct") == 50 and
                          saved.get("total") == 50 and isinstance(saved.get("payloadHash"), str) and
                          saved["payloadHash"] == closure_body.get("payloadHash"))
        else:
            consistent = False
        require(consistent, "Abgabe und Tagessperre: ein konsistenter Ausgang")

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a = pool.submit(client.call, "POST", "/quiz/attempt", accounts[1]["token"], submission)
            b = pool.submit(client.call, "DELETE", "/participants/me", accounts[1]["token"])
            attempt, deletion = a.result(), b.result()
        print(f"Abgabe/Löschung HTTP: {attempt[0]}/{deletion[0]}", flush=True)
        if receipt(deletion):
            states[1] = "deleted"
        require(receipt(deletion), "Löschung beim parallelen Test bestätigt")
        require(attempt[0] in (201, 401, 503), "Abgabe während Löschung plausibel beantwortet")
        result = client.call("GET", path, accounts[1]["token"])
        require(result[0] == 401, "Gelöschtes Konto kann Ergebnis nicht abrufen")
        result = client.call("POST", "/participants", accounts[1]["token"], {"username": accounts[1]["username"]})
        require(result[0] == 410, "Löschsperre verhindert Wiederanmeldung")
        test_ok = True
    except (RequestFailure, OSError, ValueError, KeyError, TypeError):
        print("Test nicht vollständig erfolgreich; Aufräumen folgt.", flush=True)
    finally:
        for i, account in enumerate(accounts):
            if states[i] in ("registered", "uncertain"):
                try:
                    result = client.call("DELETE", "/participants/me", account["token"])
                    if receipt(result):
                        states[i] = "deleted"
                        print(f"Konto {i+1}: Löschung bestätigt", flush=True)
                    else:
                        print(f"Konto {i+1}: Löschung nicht sicher bestätigt (HTTP {result[0]})", flush=True)
                except (RequestFailure, OSError, ValueError):
                    print(f"Konto {i+1}: Löschung nicht sicher bestätigt", flush=True)
        cleanup_ok = all(state in ("not-started", "deleted") for state in states)
        if cleanup_ok:
            try:
                recovery_path.unlink()
                print("Lokale Sicherungsdatei entfernt.", flush=True)
            except OSError:
                cleanup_ok = False
                print("Lokale Sicherungsdatei konnte nicht entfernt werden.", flush=True)
        if not cleanup_ok:
            print("Sicherungsdatei behalten: " + str(recovery_path), flush=True)
            print("Inhalt nicht öffnen oder teilen; Test nicht erneut starten.", flush=True)
    if not (test_ok and cleanup_ok):
        return False
    print("Isolierter Paralleltest abgeschlossen.", flush=True)
    return True


def main():
    try:
        key = KEY_FILE.read_text(encoding="ascii").strip()
        if not re.fullmatch(r"[0-9a-f]{64}", key):
            raise RequestFailure("Testschlüssel hat nicht das erwartete Format")
        return 0 if run(Client(key), RECOVERY_FILE) else 1
    except (RequestFailure, OSError, UnicodeError):
        print("Test nicht gestartet oder Vorprüfung fehlgeschlagen; keine Geheimnisse ausgeben.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
