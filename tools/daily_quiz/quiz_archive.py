#!/usr/bin/env python3
"""Create or verify an offline, read-only backup of a catalog's quiz questions.

No network access, publication, pruning or changes to the input snapshot.
The resulting archive contains solutions and source excerpts; protect it.
"""
import argparse
from collections import defaultdict
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import MAX_CATALOG
from sync_drive import validate_base

MAX_ARCHIVE_CONTENT = 16 * 1024 * 1024


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def expected_files(raw):
    if len(raw) > MAX_CATALOG:
        raise ValueError('Katalog überschreitet das Größenlimit')
    catalog = validate_base(json.loads(raw))
    days = defaultdict(list)
    for question in catalog['quizzes']:
        day = question['published']
        try:
            if date.fromisoformat(day).isoformat() != day:
                raise ValueError()
        except (TypeError, ValueError):
            raise ValueError('Quizdatum muss JJJJ-MM-TT entsprechen') from None
        days[day].append(question)
    files, entries = {}, []
    for day, questions in sorted(days.items()):
        name = f'quiz/{day}.json'
        data = encode({'date': day, 'quizzes': questions})
        files[name] = data
        entries.append({'date': day, 'file': name, 'questions': len(questions),
                        'sha256': hashlib.sha256(data).hexdigest()})
    files['manifest.json'] = encode({'format': 1, 'catalogRevision': catalog['revision'],
                                     'catalogSha256': hashlib.sha256(raw).hexdigest(), 'days': entries})
    if sum(map(len, files.values())) > MAX_ARCHIVE_CONTENT:
        raise ValueError('Archivinhalt zu groß')
    return files


def create(raw, destination):
    files = expected_files(raw)  # validate everything before creating a file
    path = Path(destination)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as output:
            with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for name, data in sorted(files.items()):
                    archive.writestr(name, data)
    except BaseException:
        # Only remove the incomplete file exclusively created by this invocation.
        path.unlink(missing_ok=True)
        raise
    return len(files) - 1


def verify(raw, archive_path):
    files = expected_files(raw)
    try:
        with zipfile.ZipFile(archive_path, 'r') as archive:
            infos = archive.infolist()
            if (len(infos) != len(files) or len({i.filename for i in infos}) != len(infos) or
                    set(i.filename for i in infos) != set(files) or
                    sum(i.file_size for i in infos) > MAX_ARCHIVE_CONTENT):
                raise ValueError('Archivdateien sind unvollständig oder unerwartet')
            for info in infos:
                expected = files[info.filename]
                if info.file_size != len(expected) or archive.read(info) != expected:
                    raise ValueError('Archiv stimmt nicht mit dem Katalog überein')
    except (zipfile.BadZipFile, EOFError, RuntimeError) as exc:
        raise ValueError('Archiv ist beschädigt oder nicht lesbar') from exc
    return len(files) - 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['create', 'verify'])
    parser.add_argument('catalog', type=Path, help='Unveränderter vollständiger JSON-Katalog')
    parser.add_argument('archive', type=Path, help='ZIP-Datei (create überschreibt nichts)')
    args = parser.parse_args()
    with args.catalog.open('rb') as f:
        raw = f.read(MAX_CATALOG + 1)
    count = create(raw, args.archive) if args.operation == 'create' else verify(raw, args.archive)
    print(f'{count} Quiz-Tage ' + ('gesichert.' if args.operation == 'create' else 'mit dem Katalog verglichen.'))
    print('Keine Veröffentlichung oder Entfernung. Archiv enthält Lösungen und Quellenbelege.')


if __name__ == '__main__':
    main()
