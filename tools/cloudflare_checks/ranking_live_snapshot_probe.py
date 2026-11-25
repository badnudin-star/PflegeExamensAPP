#!/usr/bin/env python3
"""Opt-in production snapshot test. Never logs credentials, quiz text or answers."""
import datetime as dt
import secrets
import signal
import sys
import time
from urllib.error import URLError
from urllib.request import build_opener
from zoneinfo import ZoneInfo

from readiness import NoRedirect
from ranking_live_probe import get
from ranking_live_account_probe import ProbeError
from ranking_provision import PreflightError
from ranking_live_attempt_probe import run


def wait_for_snapshot(opener, username, expected, now=time.time, pause=time.sleep):
    # Wait for a snapshot generated after the next whole UTC hour, not just an
    # on-demand snapshot from before submission. Cron can be delayed.
    target = (int(now()) // 3600 + 1) * 3600 * 1000
    deadline = time.monotonic() + 75 * 60
    print('::notice::Warte auf den nächsten stündlichen Ranglisten-Snapshot.', flush=True)
    while time.monotonic() < deadline:
        status, board = get(opener, '/leaderboard')
        if status != 200 or type(board.get('generatedAtMillis')) is not int or not isinstance(board.get('entries'), list):
            raise ProbeError('Ranglisten-Snapshot ungültig')
        if board['generatedAtMillis'] >= target:
            matches = [entry for entry in board['entries'] if isinstance(entry, dict) and entry.get('username') == username]
            if len(matches) == 1 and matches[0].get('points') == expected and matches[0].get('quizzes') == 1:
                print('::notice::Testpunktestand im aktualisierten öffentlichen Snapshot bestätigt.', flush=True)
                return
            if len(board['entries']) >= 100:
                raise ProbeError('Testkonto möglicherweise außerhalb der Top 100; Anzeige nicht bestätigt')
            raise ProbeError('Testpunktestand fehlt oder stimmt nicht')
        pause(30)
    raise ProbeError('Stündlicher Snapshot nicht rechtzeitig aktualisiert')


def main():
    token, username = secrets.token_hex(32), 'Probe-' + secrets.token_hex(6)
    opener = build_opener(NoRedirect())
    def interrupted(_signum, _frame):
        raise ProbeError('Test unterbrochen')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        run(opener, token, username, dt.datetime.now(ZoneInfo('Europe/Berlin')), on_recorded=wait_for_snapshot)
        status, board = get(opener, '/leaderboard')
        if status != 200 or not isinstance(board.get('entries'), list) or any(
                isinstance(entry, dict) and entry.get('username') == username for entry in board['entries']):
            raise ProbeError('Entfernung aus öffentlicher Rangliste nicht bestätigt')
        print('::notice::Nach bestätigter Löschung ist der Testname nicht mehr im öffentlichen Snapshot.', flush=True)
    except (ProbeError, PreflightError, OSError, URLError, ValueError) as exc:
        safe = {'Ranglisten-Snapshot ungültig', 'Testkonto möglicherweise außerhalb der Top 100; Anzeige nicht bestätigt',
                'Testpunktestand fehlt oder stimmt nicht', 'Stündlicher Snapshot nicht rechtzeitig aktualisiert',
                'Entfernung aus öffentlicher Rangliste nicht bestätigt', 'Test unterbrochen',
                'Kein heutiges Quiz; kein Testkonto angelegt', 'Testkontolöschung nicht bestätigt'}
        reason = str(exc) if type(exc) is ProbeError and str(exc) in safe else 'Netzwerk- oder Prüfungsfehler'
        print(f'::error::Snapshot-Test nicht vollständig erfolgreich: {reason}. Löschmeldung im Lauf beachten.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
