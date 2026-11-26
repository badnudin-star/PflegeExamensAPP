#!/usr/bin/env python3
"""Create only a new, empty, separate ranking deletion journal D1.

No production data is read or copied. Ambiguous create responses require manual
inventory before retry. This is not a live journal until separately initialized,
backfilled, integrated, checked and deployed.
"""
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler

from readiness import NoRedirect
from ranking_initialize import DB_ID
from ranking_provision import NAME as RANKING_NAME, CONTENT_ID, UUID, PreflightError, request

JOURNAL_NAME = 'pflegeexamen-ranking-loeschjournal'


def run(env, opener):
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    if (env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or env.get('CONFIRM_JOURNAL') != 'true' or not re.fullmatch(r'[0-9a-fA-F]{32}', account)
            or not token or '\n' in token or '\r' in token):
        raise PreflightError('invalid confirmation or configuration')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    ranking = request(opener, base, token, '/d1/database/' + DB_ID).get('result')
    if not isinstance(ranking, dict) or ranking.get('uuid') != DB_ID or ranking.get('name') != RANKING_NAME:
        raise PreflightError('ranking database mismatch')
    for page in range(1, 101):
        response = request(opener, base, token, f'/d1/database?per_page=100&page={page}')
        values, info = response.get('result'), response.get('result_info')
        if not isinstance(values, list) or not isinstance(info, dict): raise PreflightError('invalid inventory')
        count, current, per_page, total = (info.get(k) for k in ('count','page','per_page','total_count'))
        if (any(type(v) is not int for v in (count,current,per_page,total)) or current != page or count != len(values)
                or not 1 <= per_page <= 100 or not 0 <= total <= 10000 or count > per_page):
            raise PreflightError('invalid pagination')
        if any(not isinstance(x, dict) for x in values): raise PreflightError('invalid inventory')
        if any(x.get('name') == JOURNAL_NAME for x in values):
            raise PreflightError('journal name exists; inspect before any retry')
        if page * per_page >= total: break
        if count != per_page: raise PreflightError('incomplete inventory')
    else:
        raise PreflightError('inventory too large')
    created = request(opener, base, token, '/d1/database', 'POST', {'name':JOURNAL_NAME}).get('result')
    if (not isinstance(created, dict) or created.get('name') != JOURNAL_NAME or
            not UUID.fullmatch(str(created.get('uuid', ''))) or created['uuid'] in (DB_ID, CONTENT_ID)):
        raise PreflightError('creation result ambiguous; inspect inventory')
    print('::notice title=Neue Journal-D1::' + created['uuid'])
    print('Nur leere separate Datenbank erstellt. Journal ist noch nicht aktiv oder vollständig.')


def main():
    try:
        run(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare-Anfrage fehlgeschlagen (HTTP {exc.code}). Bei unklarem Ergebnis zuerst Bestand prüfen, nicht blind wiederholen.')
        return 1
    except (PreflightError, ValueError, KeyError, TypeError, OSError, URLError):
        print('::error::Journal nicht sicher angelegt oder Ergebnis unklar. Bestand prüfen, nicht blind wiederholen.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
