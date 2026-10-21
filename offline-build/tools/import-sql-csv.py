#!/usr/bin/env python3
"""Import selected legacy MySQL CSV exports into a new SQLite file, offline."""
import argparse
import csv
import os
from pathlib import Path
import sqlite3
import tempfile

SCHEMAS = {
    'pictify': {
        'pictify': ('CREATE TABLE pictify (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL)', ('id', 'source'), {'id'}),
    },
    'forum': {
        'core_members': ('CREATE TABLE core_members (member_id INTEGER PRIMARY KEY, email TEXT UNIQUE, member_group_id INTEGER)', ('member_id', 'email', 'member_group_id'), {'member_id', 'member_group_id'}),
        'core_pfields_content': ('CREATE TABLE core_pfields_content (member_id INTEGER PRIMARY KEY, field_2 TEXT, field_3 TEXT, field_4 TEXT)', ('member_id', 'field_2', 'field_3', 'field_4'), {'member_id'}),
    },
}

def import_csv(destination, kind, directory):
    destination, directory = Path(destination).absolute(), Path(directory)
    if destination.exists():
        raise FileExistsError('Destination already exists: ' + str(destination))
    schemas = SCHEMAS[kind]
    for table in schemas:
        if not (directory / (table + '.csv')).is_file():
            raise FileNotFoundError('Missing export: ' + str(directory / (table + '.csv')))
    destination.parent.mkdir(parents=True, exist_ok=True)
    counts = {}
    with tempfile.TemporaryDirectory(prefix='palace-sql-import-', dir=destination.parent) as staging:
        database = Path(staging) / 'database.sqlite'
        connection = sqlite3.connect(database)
        try:
            with connection:
                for table, (schema, columns, integers) in schemas.items():
                    connection.execute(schema)
                    count = 0
                    # Table and column names come only from the fixed schemas above.
                    sql = 'INSERT INTO ' + table + '(' + ','.join(columns) + ') VALUES (' + ','.join('?' for _ in columns) + ')'
                    with (directory / (table + '.csv')).open(encoding='utf-8-sig', newline='') as stream:
                        reader = csv.DictReader(stream)
                        if not reader.fieldnames or not set(columns).issubset(reader.fieldnames):
                            raise ValueError(table + ': CSV must have header columns ' + ', '.join(columns))
                        for row in reader:
                            values = []
                            for column in columns:
                                value = row[column]
                                if value is None:
                                    raise ValueError(table + ': incomplete row ' + str(reader.line_num))
                                if value == '\\N':
                                    value = None
                                elif column in integers:
                                    value = int(value)
                                values.append(value)
                            connection.execute(sql, values)
                            count += 1
                    counts[table] = count
        finally:
            connection.close()
        # Exclusive, same-filesystem publication; never replace a live database.
        os.link(database, destination)
    return counts

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('kind', choices=SCHEMAS)
    parser.add_argument('directory', type=Path, help='CSV exports with a header row; SQL NULL is \\N')
    args = parser.parse_args()
    for name, count in import_csv(args.destination, args.kind, args.directory).items():
        print(name + ': ' + str(count) + ' rows')
    print('Imported SQLite database: ' + str(args.destination))
