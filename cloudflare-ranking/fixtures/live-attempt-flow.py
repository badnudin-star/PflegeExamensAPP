"""Synthetic test for the production-probe client via a loopback-only Worker bridge."""
import contextlib
import datetime as dt
import io
from pathlib import Path
import sys
from urllib.parse import urlsplit
from urllib.request import Request, build_opener
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools' / 'cloudflare_checks'))
import ranking_live_attempt_probe as probe
from ranking_live_probe import ORIGIN


class LocalOnlyOpener:
    """Rewrite only the expected origin to an isolated worker on loopback."""
    def __init__(self, port):
        self.port = int(port)
        self.http = build_opener()

    def open(self, request, timeout):
        original = urlsplit(request.full_url)
        origin = urlsplit(ORIGIN)
        if (original.scheme, original.netloc) != (origin.scheme, origin.netloc):
            raise ValueError('unexpected origin')
        target = f'http://127.0.0.1:{self.port}' + original.path
        if original.query:
            target += '?' + original.query
        rewritten = Request(target, data=request.data, headers=dict(request.header_items()), method=request.get_method())
        return self.http.open(rewritten, timeout=timeout)


def main():
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        now = dt.datetime.now(ZoneInfo('Europe/Berlin')).replace(hour=12, minute=0, second=0, microsecond=0)
        probe.run(LocalOnlyOpener(sys.argv[1]), 'ac' * 32, 'Probe-synthetic', now)
    assert 'Registrierung, Abgabe, Wertung, Doppelabgabe, Ergebnis-Hash und Kontolöschung geprüft' in output.getvalue()
    assert 'ac' * 32 not in output.getvalue()
    print('synthetic live-attempt probe passed')


if __name__ == '__main__':
    main()
