#!/usr/bin/env python3
"""Create, verify or prepare an offline restore of quiz questions.

No network access, publication, pruning or changes to the input snapshot.
The resulting archive contains solutions and source excerpts; protect it.
"""
import argparse
from collections import defaultdict
from datetime import date
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import MAX_CATALOG
from sync_drive import validate_base

MAX_ARCHIVE_CONTENT = 16 * 1024 * 1024


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def expected_files(raw):
    # Backups must use the same unambiguous JSON rules as restoration.
    catalog = strict_catalog(raw)
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


def strict_catalog(raw):
    """Reject ambiguous JSON before producing a replacement catalog."""
    if len(raw) > MAX_CATALOG:
        raise ValueError('Katalog überschreitet das Größenlimit')

    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Doppelter JSON-Schlüssel')
            result[key] = value
        return result

    def no_nonfinite(_):
        raise ValueError('Ungültige JSON-Zahl')

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('Ungültige JSON-Zahl')
        return number

    catalog = validate_base(json.loads(raw, object_pairs_hook=no_duplicates, parse_constant=no_nonfinite, parse_float=finite_float))
    for question in catalog['quizzes']:
        day = question['published']
        try:
            if date.fromisoformat(day).isoformat() != day:
                raise ValueError()
        except (TypeError, ValueError):
            raise ValueError('Quizdatum muss JJJJ-MM-TT entsprechen') from None
    return catalog


def restore(original_raw, archive_path, target_raw, destination):
    """Prepare a new offline candidate, never edit input files or contact a server.

    Require the exact source snapshot and archive, reject conflicting quiz days/IDs,
    preserve all target learning content and do not overwrite an existing destination.
    """
    verify(original_raw, archive_path)
    original = strict_catalog(original_raw)
    target = strict_catalog(target_raw)
    if target['revision'] < original['revision'] or (target['revision'] == original['revision'] and target != original):
        raise ValueError('Zielkatalog ist älter oder widerspricht dem Ausgangskatalog gleicher Revision')

    old_days, current_days = defaultdict(list), defaultdict(list)
    for question in original['quizzes']:
        old_days[question['published']].append(question)
    for question in target['quizzes']:
        current_days[question['published']].append(question)
    current_ids = {question['id'] for question in target['quizzes']}
    recovered = []
    for day, questions in sorted(old_days.items()):
        if day in current_days:
            if {q['id']: q for q in questions} != {q['id']: q for q in current_days[day]}:
                raise ValueError(f'Quiztag {day} unterscheidet sich im Zielkatalog')
            continue
        if any(question['id'] in current_ids for question in questions):
            raise ValueError(f'Frage-ID aus {day} ist bereits einem anderen Quiztag zugeordnet')
        recovered.extend(questions)
    if not recovered:
        raise ValueError('Keine fehlenden Quizfragen wiederherzustellen')

    candidate = dict(target)
    candidate['quizzes'] = target['quizzes'] + recovered
    candidate['revision'] = target['revision'] + 1
    validate_base(candidate)
    output = encode(candidate)
    if len(output) > MAX_CATALOG:
        raise ValueError('Wiederhergestellter Katalog überschreitet das Größenlimit')

    # A complete private temporary file is linked into place without overwriting.
    # A sudden crash can leave only a temporary file; it is not a published candidate.
    path = Path(destination)
    fd, temporary = tempfile.mkstemp(prefix='.quiz-restore-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as file:
            file.write(output)
            file.flush()
            os.fsync(file.fileno())
        os.link(temporary, path)  # atomic and fails if destination already exists
    finally:
        Path(temporary).unlink(missing_ok=True)
    return len(recovered)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['create', 'verify', 'restore'])
    parser.add_argument('catalog', type=Path, help='Unveränderter vollständiger JSON-Katalog')
    parser.add_argument('archive', type=Path, help='ZIP-Datei (create überschreibt nichts)')
    parser.add_argument('--target', type=Path, help='Aktueller vollständiger Katalog für restore')
    parser.add_argument('--output', type=Path, help='Neue Ausgabedatei für restore; darf nicht existieren')
    args = parser.parse_args()
    if args.operation == 'restore' and (not args.target or not args.output):
        parser.error('restore benötigt --target und --output')
    if args.operation != 'restore' and (args.target or args.output):
        parser.error('--target und --output sind nur für restore erlaubt')
    with args.catalog.open('rb') as f:
        raw = f.read(MAX_CATALOG + 1)
    if args.operation == 'restore':
        with args.target.open('rb') as f:
            target_raw = f.read(MAX_CATALOG + 1)
        count = restore(raw, args.archive, target_raw, args.output)
        print(f'{count} Quizfragen in einem neuen lokalen Kandidaten ergänzt. Nicht veröffentlicht.')
    else:
        count = create(raw, args.archive) if args.operation == 'create' else verify(raw, args.archive)
        print(f'{count} Quiz-Tage ' + ('gesichert.' if args.operation == 'create' else 'mit dem Katalog verglichen.'))
    print('Keine Veröffentlichung oder Entfernung. Dateien enthalten Lösungen und Quellenbelege.')


if __name__ == '__main__':
    main()
