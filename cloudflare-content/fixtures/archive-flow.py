"""Synthetic integration test, run only by the local Miniflare test harness."""
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools' / 'daily_quiz'))
from archive_remote import operate
from quiz_archive import create, verify, restore
from import_notes import TOPICS

TOKEN = 'synthetic-publish-token-not-a-secret'
DAY = '2020-01-01'
QUESTION = {'id':'synthetic-1', 'published':DAY, 'topic':TOPICS[0][1], 'question':'Testfrage',
            'options':['A','B','C','D'], 'correctIndex':1, 'explanation':'Testerklärung', 'sources':['Testquelle']}
BASE = {'schemaVersion':1, 'revision':1, 'notes':[], 'cards':[], 'cases':[], 'quizzes':[QUESTION]}


class Response:
    def __init__(self, status, body): self.status_code, self.body = status, body
    def iter_content(self, size):
        for index in range(0, len(self.body), size): yield self.body[index:index+size]
    def close(self): pass


class LocalBridge:
    """Only maps the fixed synthetic origin to the local in-memory test worker."""
    def __init__(self, port): self.port, self.calls, self.lose_next = port, [], None
    def send(self, method, url, data=None, headers=None, **kwargs):
        parts = urlsplit(url)
        assert parts.scheme == 'https' and parts.netloc == 'content.test' and not parts.fragment
        assert kwargs.get('allow_redirects') is False
        self.calls.append((method, parts.path))
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=15)
        try:
            conn.request(method, parts.path + ('?' + parts.query if parts.query else ''), body=data, headers=headers or {})
            result = conn.getresponse()
            response = Response(result.status, result.read())
        finally:
            conn.close()
        if self.lose_next == method:
            self.lose_next = None
            raise ConnectionError('synthetic lost response')
        return response
    def get(self, url, **kwargs): return self.send('GET', url, **kwargs)
    def post(self, url, **kwargs): return self.send('POST', url, **kwargs)
    def put(self, url, **kwargs): return self.send('PUT', url, **kwargs)


def put_catalog(http, value, revision):
    return http.put('https://content.test/admin/content', data=json.dumps(value, ensure_ascii=False).encode(),
        headers={'Authorization':'Bearer '+TOKEN, 'Content-Type':'application/json', 'If-Match':str(revision)},
        allow_redirects=False).status_code


def get_json(http, path):
    response = http.get('https://content.test'+path, headers={}, allow_redirects=False)
    assert response.status_code == 200
    return json.loads(response.body)


def expect_failure(fn):
    try: fn()
    except (ValueError, ConnectionError): return
    raise AssertionError('expected failure')


def main():
    os.environ['GITHUB_STEP_SUMMARY'] = ''
    http = LocalBridge(int(sys.argv[1]))
    raw = json.dumps(BASE, ensure_ascii=False).encode()
    with tempfile.TemporaryDirectory() as temp:
        backup, candidate = Path(temp)/'backup.zip', Path(temp)/'candidate.json'
        assert create(raw, backup) == 1 and verify(raw, backup) == 1
        assert put_catalog(http, BASE, 0) == 200
        args = (http, 'https://content.test', TOKEN, raw, backup, DAY)
        http.lose_next = 'POST'
        expect_failure(lambda: operate(*args, 'copy'))
        assert get_json(http, '/content') == BASE
        assert get_json(http, '/quiz-archive/'+DAY) == {'date':DAY, 'quizzes':[QUESTION]}
        assert operate(*args, 'copy') == 1  # explicitly repeat the already committed copy
        expect_failure(lambda: operate(*args, 'prune'))
        http.lose_next = 'PUT'
        expect_failure(lambda: operate(*args, 'prune', confirm_compatibility=True))
        pruned = get_json(http, '/content')
        assert pruned == {**BASE, 'revision':2, 'quizzes':[]}
        before = len(http.calls)
        expect_failure(lambda: operate(*args, 'prune', confirm_compatibility=True))
        assert all(method != 'PUT' for method, _ in http.calls[before:])
        assert get_json(http, '/quiz-archive/'+DAY) == {'date':DAY, 'quizzes':[QUESTION]}
        assert restore(raw, backup, json.dumps(pruned, ensure_ascii=False).encode(), candidate) == 1
        restored = json.loads(candidate.read_bytes())
        assert restored == {**BASE, 'revision':3}
        # This publication is performed only inside the in-memory test, never by restore().
        assert put_catalog(http, restored, 2) == 200
        assert get_json(http, '/content') == restored
        assert get_json(http, '/quiz-archive/'+DAY) == {'date':DAY, 'quizzes':[QUESTION]}
        bad = Path(temp)/'bad.zip'
        bad.write_bytes(b'not a zip')
        before = len(http.calls)
        expect_failure(lambda: operate(http, 'https://content.test', TOKEN, raw, bad, DAY, 'copy'))
        assert len(http.calls) == before
        assert verify(raw, backup) == 1
    print('synthetic archive flow passed')


if __name__ == '__main__': main()
