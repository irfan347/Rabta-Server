"""Exercise the actual Android carry-store schemas against a file-backed SQLite DB."""
import pathlib,re,sqlite3,tempfile,unittest
SOURCE=(pathlib.Path(__file__).parents[1]/'app/src/main/java/com/irfan/rabta/MeshStore.kt').read_text()
class MeshStorageTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.path=pathlib.Path(self.tmp.name)/'mesh.db'
        self.db=sqlite3.connect(self.path)
        for sql in re.findall(r'd.execSQL\("(CREATE TABLE [^"]+)"\)',SOURCE):self.db.execute(sql)
    def tearDown(self):self.db.close();self.tmp.cleanup()
    def test_carry_and_replay_cache_survive_process_restart(self):
        self.db.execute("INSERT INTO packets VALUES(?,?,?,?)",('id','encrypted-envelope',200,1))
        self.db.execute("INSERT INTO seen VALUES(?,?)",('id',200));self.db.commit();self.db.close()
        self.db=sqlite3.connect(self.path)
        self.assertEqual(self.db.execute('SELECT json FROM packets').fetchone()[0],'encrypted-envelope')
        self.assertEqual(self.db.execute('SELECT id FROM seen').fetchone()[0],'id')
    def test_duplicate_ids_are_unique(self):
        self.db.execute("INSERT OR IGNORE INTO packets VALUES('id','one',200,0)")
        self.db.execute("INSERT OR IGNORE INTO packets VALUES('id','two',200,0)")
        self.assertEqual(self.db.execute('SELECT count(*) FROM packets').fetchone()[0],1)
    def test_expiry_selects_own_packets_and_waiting_messages_only(self):
        sql=re.search(r'rawQuery\("(SELECT id FROM packets WHERE expires[^\"]+)"',SOURCE).group(1)
        self.db.executemany('INSERT INTO packets VALUES(?,?,?,?)',[('own','e',99,1),('relay','e',99,0),('fresh','e',101,1)])
        self.db.execute("INSERT INTO pending VALUES('waiting','peer','text','text',1)")
        self.assertEqual({r[0] for r in self.db.execute(sql,(100,2))},{'own','waiting'})
