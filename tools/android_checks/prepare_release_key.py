#!/usr/bin/env python3
"""Write an Actions-provided signing key privately to the disposable runner only."""
import base64
import binascii
import os
from pathlib import Path
import sys

MAX_KEY_BYTES = 32 * 1024  # base64 representation stays below GitHub's secret size limit
KEY_FILENAME = 'pflege-release-signing.keystore'
REQUIRED = ('PFLEGE_RELEASE_KEYSTORE_BASE64', 'PFLEGE_RELEASE_STORE_PASSWORD',
            'PFLEGE_RELEASE_KEY_ALIAS', 'PFLEGE_RELEASE_KEY_PASSWORD')


def prepare(env):
    if any(not env.get(name, '').strip() for name in REQUIRED):
        raise ValueError('Release-Signatur unvollständig eingerichtet.')
    encoded = env['PFLEGE_RELEASE_KEYSTORE_BASE64']
    if len(encoded) > ((MAX_KEY_BYTES + 2) // 3) * 4:
        raise ValueError('Schlüsseldatei zu groß.')
    try:
        key = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError('Schlüsseldatei ist kein gültiges Base64.') from None
    if not key or len(key) > MAX_KEY_BYTES:
        raise ValueError('Schlüsseldatei leer oder zu groß.')
    root = Path(env.get('RUNNER_TEMP', ''))
    if not env.get('RUNNER_TEMP') or not root.is_absolute() or not root.is_dir():
        raise ValueError('Temporäres Runner-Verzeichnis fehlt.')
    path = root / KEY_FILENAME
    # Exclusive creation also rejects a pre-existing file or symlink.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, 'wb') as file:
            file.write(key)
            file.flush()
            os.fsync(file.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return path


def main():
    try:
        prepare(os.environ)
    except Exception:
        # Do not expose secrets, decoded bytes, paths, or exception details.
        print('::error::Release-Schlüssel konnte nicht sicher vorbereitet werden.')
        return 1
    print('Release-Schlüssel für diesen Lauf vorbereitet.')
    return 0


if __name__ == '__main__': sys.exit(main())
