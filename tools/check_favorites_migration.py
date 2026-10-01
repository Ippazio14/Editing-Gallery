"""Regression check for v10 -> v11: removing favorites must preserve media and trash."""
import re
import sqlite3
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/org/fossify/gallery/databases/GalleryDatabase.kt').read_text()
migration = source.split('private val MIGRATION_10_11')[1].split('private val MIGRATION_9_10')[0]
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT, filename TEXT NOT NULL, full_path TEXT NOT NULL, parent_path TEXT NOT NULL,last_modified INTEGER NOT NULL,date_taken INTEGER NOT NULL,size INTEGER NOT NULL,type INTEGER NOT NULL,video_duration INTEGER NOT NULL,is_favorite INTEGER NOT NULL,deleted_ts INTEGER NOT NULL,media_store_id INTEGER NOT NULL)')
db.execute('CREATE TABLE directories(path TEXT)')
db.execute('CREATE TABLE favorites(id INTEGER)')
rows = [(1,'photo.jpg','/Pictures/photo.jpg','/Pictures',123,456,987,1,0,1,0,777), (2,'trash.jpg','/bin/trash.jpg','/bin',456,789,654,1,0,0,100,778)]
db.executemany('INSERT INTO media VALUES(?,?,?,?,?,?,?,?,?,?,?,?)', rows)
db.executemany('INSERT INTO directories VALUES(?)', [('favorites',),('/Pictures',),('recycle_bin',)])
for sql in re.findall(r'database.execSQL\("([^"]+)"\)', migration):
    db.execute(sql)
expected = [row[:9] + row[10:] for row in rows]
assert db.execute('SELECT * FROM media ORDER BY id').fetchall() == expected
assert 'is_favorite' not in [col[1] for col in db.execute('PRAGMA table_info(media)')]
assert not db.execute("SELECT name FROM sqlite_master WHERE name='favorites'").fetchall()
assert db.execute('SELECT path FROM directories ORDER BY path').fetchall() == [('/Pictures',),('recycle_bin',)]
try:
    db.execute("INSERT INTO media SELECT 3, filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, deleted_ts, media_store_id FROM media WHERE id=1")
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError('Unique media path constraint was lost')
print('Migration OK: media, trash, identifiers and unique paths preserved; favorites removed.')
