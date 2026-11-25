#!/usr/bin/env python3
"""Exercise administrative deletion against the previously created synthetic D1 only."""
import os
import re
import secrets
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_admin_delete import delete_verified
from ranking_initialize import DB_ID, OBJECTS, OBJECT_SQL
from ranking_provision import CONTENT_ID, PreflightError, request

TARGET = 'bfba37e8-ed80-4af7-b9ad-4be5d49ebf95'
NAME = 'pflege-ranking-restore-synthetic-8d744940bd4236fa'


def run(env, opener):
    account, token = env.get('CLOUDFLARE_ACCOUNT_ID','').strip(), env.get('CLOUDFLARE_API_TOKEN','').strip()
    if not re.fullmatch(r'[0-9a-fA-F]{32}', account) or not token or env.get('ADMIN_TEST_CONFIRM') != 'true':
        raise PreflightError('invalid configuration')
    if TARGET in (DB_ID, CONTENT_ID): raise PreflightError('production target forbidden')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    info = request(opener,base,token,'/d1/database/' + TARGET).get('result')
    if not isinstance(info,dict) or info.get('uuid') != TARGET or info.get('name') != NAME:
        raise PreflightError('target mismatch')
    path = '/d1/database/' + TARGET + '/query'
    def q(sql, params=None):
        result = request(opener,base,token,path,'POST',{'sql':sql,'params':params or []}).get('result')
        if not isinstance(result,list) or len(result) != 1 or result[0].get('success') is not True:
            raise PreflightError('bad query')
        return result[0].get('results',[])
    if {(r['type'],r['name']) for r in q(OBJECT_SQL)} != OBJECTS: raise PreflightError('schema mismatch')
    fixture = q("SELECT COUNT(*) AS n FROM quiz_participants WHERE id='fixture-alive' AND token_hash=?", ['a'*64])
    if fixture != [{'n':1}]: raise PreflightError('fixture mismatch')
    # A duplicate insert in a D1 batch must not leave the first synthetic insert.
    trial = secrets.token_hex(32)
    batch = [{'sql':'INSERT INTO quiz_deleted_tokens VALUES (?, ?)', 'params':[trial,1]},
             {'sql':'INSERT INTO quiz_deleted_tokens VALUES (?, ?)', 'params':[trial,1]}]
    failed = False
    try:
        response = request(opener,base,token,path,'POST',{'batch':batch})
        failed = any(x.get('success') is not True for x in response.get('result',[]) if isinstance(x,dict))
    except (HTTPError, PreflightError):
        failed = True
    if not failed or q('SELECT COUNT(*) AS n FROM quiz_deleted_tokens WHERE token_hash=?',[trial]) != [{'n':0}]:
        raise PreflightError('batch rollback not confirmed')
    print('::notice::D1-Batch mit synthetischem Fehler wurde vollständig zurückgerollt.',flush=True)
    ids, hashes = [str(uuid.uuid4()), str(uuid.uuid4())], [secrets.token_hex(32),secrets.token_hex(32)]
    print('::notice::Die folgende Prüfung verändert nur neu erzeugte synthetische Konten in der Test-D1.',flush=True)
    for i in range(2):
        q('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', [ids[i],hashes[i],f'Synthetic-Admin-{i}',1])
        q('INSERT INTO quiz_attempts VALUES (?, ?, ?, ?, ?, ?)',[ids[i],'2026-01-01',i+1,50,1,None])
    q('INSERT INTO quiz_attempt_closures VALUES (?, ?, ?)',[ids[0],'2026-01-02',1])
    q("INSERT INTO quiz_hourly_snapshot VALUES (1, '{}', 1) ON CONFLICT(id) DO UPDATE SET payload_json='{}', generated_at=1")
    delete_verified(opener,base,token,TARGET,NAME,ids[0],hashes[0])
    untouched = q('SELECT COUNT(*) AS n FROM quiz_participants p JOIN quiz_attempts a ON p.id=a.participant_id WHERE p.id=? AND p.token_hash=? AND a.correct=2',[ids[1],hashes[1]])
    if untouched != [{'n':1}]: raise PreflightError('other account changed')
    if q('SELECT COUNT(*) AS n FROM quiz_hourly_snapshot') != [{'n':0}]: raise PreflightError('snapshot not invalidated')
    delete_verified(opener,base,token,TARGET,NAME,ids[1],hashes[1])
    print('::notice::Gezielte administrative Löschung, Sperre, Snapshot-Invalidierung und Erhalt des anderen Testkontos auf echter Test-D1 geprüft; beide Testkonten gelöscht.',flush=True)


def main():
    try: run(os.environ,build_opener(ProxyHandler({}),NoRedirect()))
    except (HTTPError,PreflightError,URLError,OSError,ValueError,KeyError,TypeError):
        print('::error::Synthetischer Admin-Test nicht bestätigt. Test-D1 prüfen, nicht blind wiederholen; Produktion blieb unberührt.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
