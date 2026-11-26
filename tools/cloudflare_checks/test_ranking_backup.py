import copy
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import ranking_backup as backup
from ranking_provision import PreflightError

SOURCE = '12345678-1234-1234-1234-123456789abc'
NAME = 'synthetic-fixture'
PASSWORD = 'synthetic passphrase test only'


def fixture():
    return {'format':1,'source_id':SOURCE,'source_name':NAME,'captured_at':'2026-01-01T00:00:00+00:00','generation':3,
        'participants':[{'id':'id1','token_hash':'a'*64,'username':'Synthetic-Private','created_at':1}],
        'attempts':[{'participant_id':'id1','quiz_date':'2026-01-01','correct':3,'total':50,'submitted_at':2,'payload_hash':'b'*64}],
        'closures':[{'participant_id':'id1','quiz_date':'2026-01-02','closed_at':3}],
        'barriers':[{'token_hash':'c'*64,'deleted_at':4}]}

class FakeApi:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript(backup.SCHEMA_PATH.read_text())
        for key in ('barriers','participants','attempts','closures'):
            row = fixture()[key][0]; fields = backup.FIELDS[key]
            self.db.execute('INSERT INTO '+backup.TABLES[key]+' VALUES ('+','.join('?' for _ in fields)+')', [row[f] for f in fields])
        self.paths=[]
    def call(self, opener, base, token, path, method='GET', data=None):
        self.paths.append((path,method))
        if path == '/d1/database/'+SOURCE: return {'result':{'uuid':SOURCE,'name':NAME}}
        if path != '/d1/database/'+SOURCE+'/query': raise AssertionError('unexpected target')
        rows = self.db.execute(data['sql']).fetchall()
        return {'result':[{'success':True,'results':[dict(row) for row in rows]}]}

class BackupTests(unittest.TestCase):
    def test_capture_is_read_only_and_single_snapshot(self):
        api = FakeApi()
        with patch.object(backup,'request',side_effect=api.call):
            data = backup.capture(None,'base','secret',SOURCE,NAME)
        self.assertEqual(data['participants'],fixture()['participants'])
        self.assertEqual(data['barriers'],fixture()['barriers'])
        self.assertEqual(len(api.paths),3)
        self.assertEqual(api.paths[-1],('/d1/database/'+SOURCE+'/query','POST'))
        self.assertNotIn('secret', str(data))

    def test_guarded_schema_keeps_format_and_rejects_outstanding_permit(self):
        from ranking_journal_backfill import GUARD
        api=FakeApi(); api.db.executescript(GUARD.read_text())
        with patch.object(backup,'request',side_effect=api.call):
            data=backup.capture(None,'base','secret',SOURCE,NAME)
        self.assertEqual(data['participants'],fixture()['participants'])
        self.assertEqual(backup.validate(fixture(),SOURCE,NAME),fixture())
        api.db.execute('INSERT INTO quiz_journal_delete_permits VALUES (?)', ('d'*64,))
        with patch.object(backup,'request',side_effect=api.call), self.assertRaises(PreflightError):
            backup.capture(None,'base','secret',SOURCE,NAME)

    def test_conflicting_or_inconsistent_rows_rejected(self):
        for change in ('deleted_live','orphan','bad_hash','same_day_closure','bad_number','bad_date'):
            data=copy.deepcopy(fixture())
            if change == 'deleted_live': data['barriers'][0]['token_hash']='a'*64
            if change == 'orphan': data['attempts'][0]['participant_id']='missing'
            if change == 'bad_hash': data['participants'][0]['token_hash']='short'
            if change == 'same_day_closure': data['closures'][0]['quiz_date']='2026-01-01'
            if change == 'bad_number': data['attempts'][0]['correct']=True
            if change == 'bad_date': data['attempts'][0]['quiz_date']='2026-02-30'
            with self.subTest(change=change), self.assertRaises(ValueError): backup.validate(data,SOURCE,NAME)

    def test_wrong_source_and_duplicate_json_rejected(self):
        with self.assertRaises(ValueError): backup.validate(fixture(),SOURCE,'other')
        with self.assertRaises(ValueError): backup.strict_json('{"x":1,"x":2}')
        api=FakeApi()
        with patch.object(backup,'request',side_effect=api.call), self.assertRaises(PreflightError):
            backup.capture(None,'base','secret',SOURCE,'wrong')
        self.assertEqual(len(api.paths),1)

    def test_encryption_authenticates_and_never_overwrites(self):
        data=fixture(); blob=backup.encrypt(data,PASSWORD)
        self.assertNotIn(b'Synthetic-Private',blob)
        self.assertEqual(backup.decrypt(blob,PASSWORD,SOURCE,NAME),data)
        with self.assertRaises(ValueError): backup.decrypt(blob,'incorrect passphrase long enough',SOURCE,NAME)
        damaged=bytearray(blob); damaged[-1] ^= 1
        with self.assertRaises(ValueError): backup.decrypt(bytes(damaged),PASSWORD,SOURCE,NAME)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'backup.rankbackup'
            backup.write_backup(path,blob,PASSWORD,SOURCE,NAME)
            self.assertEqual(os.stat(path).st_mode & 0o777,0o600)
            with self.assertRaises(FileExistsError): backup.write_backup(path,blob,PASSWORD,SOURCE,NAME)
            self.assertEqual(path.read_bytes(),blob)
            failed=Path(temp)/'failed.rankbackup'
            with self.assertRaises(ValueError): backup.write_backup(failed,bytes(damaged),PASSWORD,SOURCE,NAME)
            self.assertFalse(failed.exists())

    def test_path_and_password_guards(self):
        with self.assertRaises(ValueError): backup.safe_path('relative.rankbackup')
        with self.assertRaises(ValueError): backup.safe_path(str(backup.SCHEMA_PATH.parents[1]/'unsafe.rankbackup'))
        with self.assertRaises(ValueError): backup.encrypt(fixture(),'short')
        with self.assertRaises(ValueError): backup.decrypt(b'invalid',PASSWORD,SOURCE,NAME)

if __name__ == '__main__': unittest.main()
