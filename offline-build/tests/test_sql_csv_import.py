import csv
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('import_sql', Path(__file__).resolve().parents[1] / 'tools/import-sql-csv.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.destination = self.directory / 'new.sqlite'

    def write(self, name, rows):
        with (self.directory / (name + '.csv')).open('w', newline='', encoding='utf-8') as stream:
            csv.writer(stream).writerows(rows)

    def test_pictify_roundtrip(self):
        self.write('pictify', [('id','source'),(42,'https://example.test/a?x=1,y=2')])
        self.assertEqual(module.import_csv(self.destination, 'pictify', self.directory), {'pictify':1})
        with sqlite3.connect(self.destination) as db:
            self.assertEqual(db.execute('SELECT id,source FROM pictify').fetchone(), (42,'https://example.test/a?x=1,y=2'))

    def test_forum_roundtrip_and_null(self):
        self.write('core_members', [('member_id','email','member_group_id'), (7,'user@example.test',3)])
        self.write('core_pfields_content', [('member_id','field_2','field_3','field_4'), (7,'\\N','uuid','name')])
        module.import_csv(self.destination, 'forum', self.directory)
        with sqlite3.connect(self.destination) as db:
            self.assertEqual(db.execute('SELECT field_2,field_4 FROM core_pfields_content').fetchone(), (None,'name'))

    def test_existing_destination_preserved(self):
        self.destination.write_bytes(b'original')
        with self.assertRaises(FileExistsError):module.import_csv(self.destination, 'pictify', self.directory)
        self.assertEqual(self.destination.read_bytes(), b'original')

    def test_duplicate_rolls_back_without_publication(self):
        self.write('pictify', [('id','source'), (1,'a'), (1,'b')])
        with self.assertRaises(sqlite3.IntegrityError):module.import_csv(self.destination, 'pictify', self.directory)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.directory.glob('palace-sql-import-*')))

    def test_missing_export_fails_before_creation(self):
        with self.assertRaises(FileNotFoundError):module.import_csv(self.destination, 'forum', self.directory)
        self.assertFalse(self.destination.exists())

if __name__ == '__main__':unittest.main()
