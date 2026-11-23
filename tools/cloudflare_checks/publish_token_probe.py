"""Probe the configured publisher credential without publishing or archiving a quiz.

The endpoint and absent-date guard are deliberately fixed; no token or catalogue
contents are printed. This probe is opt-in, not a normal readiness check.
"""
import json
import os
import time
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

BASE = "https://pflegeexamen-content.badnudin.workers.dev"
DAY = "1900-01-01"
MAX_CATALOG = 9 * 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check(token, opener=None):
    if not isinstance(token, str) or len(token) < 32 or "\r" in token or "\n" in token:
        return "GitHub-Secret fehlt oder ist ungültig."
    opener = opener or build_opener(NoRedirect())
    try:
        request = Request(
            BASE + "/content?probe=" + str(time.time_ns()),
            headers={"User-Agent": "PflegeApp-Tokenprobe", "Cache-Control": "no-cache"},
        )
        with opener.open(request, timeout=30) as response:
            if response.status != 200:
                return "Katalogprüfung fehlgeschlagen."
            raw = response.read(MAX_CATALOG + 1)
        if len(raw) > MAX_CATALOG:
            return "Katalogprüfung fehlgeschlagen."
        catalog = json.loads(raw)
        revision = catalog.get("revision")
        quizzes = catalog.get("quizzes")
        if (catalog.get("schemaVersion") != 1 or type(revision) is not int or revision < 1
                or not isinstance(quizzes, list)
                or any(not isinstance(q, dict) or "published" not in q for q in quizzes)
                or any(q["published"] == DAY for q in quizzes)):
            return "Sicherheitsprüfung fehlgeschlagen; kein Token-Test gesendet."
        request = Request(
            BASE + "/admin/quiz-archive/" + DAY,
            method="POST", data=b"",
            headers={
                "User-Agent": "PflegeApp-Tokenprobe",
                "Authorization": "Bearer " + token,
                "If-Match": str(revision),
            },
        )
        try:
            with opener.open(request, timeout=30) as response:
                status = response.status
                body = response.read(4097)
        except HTTPError as error:
            status = error.code
            body = error.read(4097)
        if status == 404 and len(body) <= 4096:
            try:
                if json.loads(body) == {"error": "Quiztag nicht im Katalog."}:
                    return "Token akzeptiert; Prüftag nicht vorhanden."
            except (ValueError, TypeError):
                pass
        if status == 401:
            return "Token nicht akzeptiert."
        if status == 409:
            return "Katalogrevision geändert; Probe nicht bestätigt."
        return "Unerwartete Antwort; Probe nicht bestätigt."
    except Exception:
        # No exception text: network errors may contain sensitive request details.
        return "Tokenprobe fehlgeschlagen; keine Details ausgegeben."


def main():
    result = check(os.environ.get("CONTENT_PUBLISH_TOKEN", ""))
    print(result)
    return 0 if result == "Token akzeptiert; Prüftag nicht vorhanden." else 1


if __name__ == "__main__":
    raise SystemExit(main())
