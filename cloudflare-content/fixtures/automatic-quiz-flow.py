"""Synthetic, local-only integration of automatic publisher with Miniflare D1."""
import copy
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import sys
from zoneinfo import ZoneInfo

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('archive_flow', here / 'archive-flow.py')
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
sys.path.insert(0, str(here.parents[1] / 'tools' / 'daily_quiz'))
import publish_automatic as automatic
from test_quiz_pipeline import QuizTests


def main():
    os.environ['GITHUB_STEP_SUMMARY'] = ''
    http = bridge.LocalBridge(int(sys.argv[1]))
    base = {'schemaVersion':1, 'revision':1, 'notes':[], 'cards':[], 'cases':[], 'quizzes':[]}
    draft = QuizTests().draft()
    clock = lambda: dt.datetime.fromisoformat(draft['date'] + 'T12:00:00').replace(tzinfo=ZoneInfo('Europe/Berlin'))
    args = (http, 'https://content.test', bridge.TOKEN, draft, base, clock)
    try:
        automatic.publish(*args)
        raise AssertionError('missing catalog must fail closed')
    except ValueError:
        pass
    assert bridge.put_catalog(http, base, 0) == 200
    http.lose_next = 'PUT'
    assert automatic.publish(*args) == 'confirmed-after-uncertain-response'
    actual = bridge.get_json(http, '/content')
    assert actual == {**base, 'revision':2, 'quizzes':draft['quizzes']}
    count = len([method for method, _ in http.calls if method == 'PUT'])
    assert automatic.publish(*args) == 'already-published'
    assert len([method for method, _ in http.calls if method == 'PUT']) == count
    changed = copy.deepcopy(draft)
    changed['quizzes'][0]['explanation'] += ' verändert'
    try:
        automatic.publish(http, 'https://content.test', bridge.TOKEN, changed, base, clock)
        raise AssertionError('existing day must not be overwritten')
    except ValueError:
        pass
    assert bridge.get_json(http, '/content') == actual
    print('synthetic automatic quiz flow passed')


if __name__ == '__main__': main()
