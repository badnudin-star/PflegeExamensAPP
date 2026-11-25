#!/usr/bin/env python3
"""Preflight a FIRST production Worker deployment, without altering resources."""
import os
import re
import sys
import tomllib
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import build_opener
from readiness import NoRedirect
from ranking_provision import CONTENT_ID, NAME, WORKER, PreflightError, request
from ranking_initialize import DB_ID, OBJECTS, objects, query

CONFIG = Path(__file__).resolve().parents[2] / 'cloudflare-ranking/wrangler.production.toml'


def verify_config():
    with CONFIG.open('rb') as file:
        config = tomllib.load(file)
    if (set(config) != {'name','main','compatibility_date','workers_dev','preview_urls','observability','triggers','vars','services','d1_databases','ratelimits'} or
        config.get('name') != WORKER or config.get('main') != 'src/worker.js' or
        config.get('compatibility_date') != '2025-11-01' or config.get('observability') != {'enabled':False} or
        config.get('workers_dev') is not True or config.get('preview_urls') is not False or
        config.get('vars') != {'QUIZ_CATALOG_URL':'https://pflegeexamen-content.badnudin.workers.dev/content', 'QUIZ_CATALOG_TRANSPORT':'service'} or
        config.get('services') != [{'binding':'QUIZ_CATALOG_SERVICE', 'service':'pflegeexamen-content'}] or
        config.get('d1_databases') != [{'binding':'DB', 'database_name':NAME, 'database_id':DB_ID}] or
        config.get('triggers') != {'crons':['0 * * * *']}):
        raise PreflightError('production configuration mismatch')
    limits = config.get('ratelimits')
    expected = [('REGISTRATION_RATE','74751001',5), ('READ_RATE','74751002',120),
                ('AUTH_RATE','74751003',60), ('ACCOUNT_RATE','74751004',20)]
    if limits != [{'name':n, 'namespace_id':i, 'simple':{'limit':v, 'period':60}} for n, i, v in expected]:
        raise PreflightError('rate limit configuration mismatch')


def run(env, opener):
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account) or env.get('RANKING_CONFIRM') != 'true':
        raise PreflightError('missing configuration or confirmation')
    verify_config()
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    content = request(opener, base, token, '/workers/scripts/pflegeexamen-content/settings').get('result')
    bindings = content.get('bindings') if isinstance(content, dict) else None
    if not isinstance(bindings, list) or not any(isinstance(b, dict) and b.get('name') == 'DB' and
        b.get('type') == 'd1' and b.get('id', b.get('database_id')) == CONTENT_ID for b in bindings):
        raise PreflightError('content database binding mismatch')
    db = request(opener, base, token, '/d1/database/' + DB_ID).get('result')
    if not isinstance(db, dict) or db.get('uuid') != DB_ID or db.get('name') != NAME or DB_ID == CONTENT_ID:
        raise PreflightError('ranking database identity mismatch')
    if objects(opener, base, token) != OBJECTS:
        raise PreflightError('ranking schema mismatch')
    counts = query(opener, base, token, '''SELECT
      (SELECT COUNT(*) FROM quiz_participants) AS participants,
      (SELECT COUNT(*) FROM quiz_attempts) AS attempts,
      (SELECT COUNT(*) FROM quiz_deleted_tokens) AS deleted,
      (SELECT COUNT(*) FROM quiz_attempt_closures) AS closures''')
    if len(counts) != 1 or counts[0].get('results') != [{'participants':0, 'attempts':0, 'deleted':0, 'closures':0}]:
        raise PreflightError('ranking database is not newly initialized')
    try:
        request(opener, base, token, '/workers/scripts/' + WORKER + '/settings')
    except HTTPError as exc:
        if exc.code != 404: raise
    else:
        raise PreflightError('worker already exists; refuse first deployment')
    subdomain = request(opener, base, token, '/workers/subdomain').get('result')
    if not isinstance(subdomain, dict) or subdomain.get('subdomain') != 'badnudin':
        raise PreflightError('unexpected workers.dev subdomain')
    print('Inhalts-Worker, separate initialisierte D1 und freier Worker-Name geprüft.')


def main():
    try:
        run(os.environ, build_opener(NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare-Vorprüfung HTTP {exc.code}; kein Deployment begonnen.')
        return 1
    except (ValueError, KeyError, TypeError, OSError, URLError):
        print('::error::Cloudflare-Vorprüfung nicht bestätigt; kein Deployment begonnen.')
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
