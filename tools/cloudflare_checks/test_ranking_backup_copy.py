import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import ranking_backup as backup
import ranking_backup_copy as copy
from test_ranking_backup import fixture, SOURCE, NAME, PASSWORD


class BackupCopyTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source.rankbackup'
        self.target = self.root / 'usb.rankbackup'
        backup.write_backup(self.source, backup.encrypt(fixture(), PASSWORD), PASSWORD, SOURCE, NAME)

    def test_identical_authenticated_copy_without_plaintext_or_overwrite(self):
        copy.copy_verified(self.source, self.target, PASSWORD, SOURCE, NAME)
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())
        self.assertNotIn(b'Synthetic-Private', self.target.read_bytes())
        self.assertEqual(backup.decrypt(self.target.read_bytes(), PASSWORD, SOURCE, NAME), fixture())
        with self.assertRaises(FileExistsError):
            copy.copy_verified(self.source, self.target, PASSWORD, SOURCE, NAME)

    def test_wrong_password_or_corrupt_source_does_not_create_copy(self):
        with self.assertRaises(ValueError):
            copy.copy_verified(self.source, self.target, 'wrong passphrase longer than sixteen', SOURCE, NAME)
        self.assertFalse(self.target.exists())
        blob = bytearray(self.source.read_bytes()); blob[-1] ^= 1
        self.source.write_bytes(blob)
        with self.assertRaises(ValueError):
            copy.copy_verified(self.source, self.target, PASSWORD, SOURCE, NAME)
        self.assertFalse(self.target.exists())

    def test_same_path_and_symlink_rejected(self):
        with self.assertRaises(ValueError):
            copy.copy_verified(self.source, self.source, PASSWORD, SOURCE, NAME)
        symlink = self.root / 'link.rankbackup'
        symlink.symlink_to(self.source)
        with self.assertRaises(OSError):
            copy.copy_verified(symlink, self.target, PASSWORD, SOURCE, NAME)
        self.assertFalse(self.target.exists())
        self.target.symlink_to(self.source)
        with self.assertRaises(FileExistsError):
            copy.copy_verified(self.source, self.target, PASSWORD, SOURCE, NAME)
        self.assertTrue(self.target.is_symlink())

    def test_special_file_and_oversized_file_rejected(self):
        fifo = self.root / 'fifo.rankbackup'
        os.mkfifo(fifo)
        with self.assertRaises(ValueError): copy.read_source(fifo)
        self.source.write_bytes(b'x' * (backup.MAX_FILE + 1))
        with self.assertRaises(ValueError): copy.read_source(self.source)


if __name__ == '__main__': unittest.main()
