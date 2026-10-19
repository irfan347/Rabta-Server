"""Exercise the production SQLite statements against realistic message histories."""
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1]/"app/src/main/java/com/irfan/rabta/Db.kt").read_text()

def sql_in(function, prefix):
    body = SOURCE.split("fun " + function + "(", 1)[1].split("\n    fun ",1)[0]
    matches = re.findall(r'"""(.*?)"""|"([^"\n]+)"', body, re.S)
    return next((long or short).strip() for long,short in matches if (long or short).strip().startswith(prefix))

class MessageQueryTests(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        for statement in re.findall(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS [^"]+)"\)',SOURCE):
            self.db.execute(statement)
        self.db.execute("INSERT INTO conversations(peer_id,peer_name) VALUES ('ali','Ali')")
        self.db.execute("INSERT INTO conversations(peer_id,peer_name) VALUES ('amna','Amna')")

    def message(self,id,conv='ali',body='hello',time=1,mine=1,status='sent',kind='text'):
        self.db.execute("INSERT INTO messages(id,conv_id,from_id,body,sent_at,mine,status,msg_type) VALUES (?,?,?,?,?,?,?,?)",(str(id),conv,'me',body,time,mine,status,kind))

    def search(self,query):
        return self.db.execute(sql_in('searchConversations','SELECT c.peer_id'),(query,)*4).fetchall()

    def test_search_matches_old_messages_not_just_latest_preview(self):
        self.message('old',body='Train tickets',time=1)
        self.message('new',body='Hello',time=2)
        self.assertEqual(self.search('TICKETS')[0][0],'ali')
        self.assertEqual(self.search('tickets')[0][6],'Train tickets')

    def test_names_match_and_media_base64_is_excluded(self):
        self.message('image',body='hiddenneedle',kind='image')
        self.assertEqual(self.search('AMNA')[0][0],'amna')
        self.assertEqual(self.search('hiddenneedle'),[])

    def test_search_treats_sql_metacharacters_as_literal(self):
        self.message('percent',body='100% ready')
        self.assertEqual(len(self.search('%')),1)
        self.assertEqual(self.search("' OR 1=1 --"),[])
        self.assertEqual(self.search('_'),[])

    def test_history_loads_newest_200_not_oldest_200(self):
        for i in range(250): self.message(i,time=i)
        rows=self.db.execute(sql_in('messages','SELECT id'),('ali','200')).fetchall()
        self.assertEqual(len(rows),200)
        self.assertEqual(rows[0][0],'249')
        self.assertEqual(rows[-1][0],'50')

    def test_individual_receipt_only_changes_sender_message_in_matching_chat(self):
        self.message('a',status='sent')
        self.message('b',conv='amna',status='sent')
        self.message('incoming',mine=0,status='received')
        statement=sql_in('applyReadReceipt',"UPDATE messages SET status='read' WHERE id=")
        self.db.execute(statement,('b','ali'))
        self.db.execute(statement,('incoming','ali'))
        self.db.execute(statement,('a','ali'))
        states=dict(self.db.execute('SELECT id,status FROM messages'))
        self.assertEqual(states,{'a':'read','b':'sent','incoming':'received'})

    def test_bulk_read_does_not_claim_unsent_or_failed_messages_were_read(self):
        for state in ('sent','relayed','delivered','direct_delivered','failed','sending','queued','mesh'): self.message(state,status=state)
        self.db.execute(sql_in('applyReadReceipt',"UPDATE messages SET status='read' WHERE conv_id="),('ali',))
        states=dict(self.db.execute('SELECT id,status FROM messages'))
        self.assertEqual(states,{'sent':'read','relayed':'read','delivered':'read','direct_delivered':'read','failed':'failed','sending':'sending','queued':'queued','mesh':'mesh'})


    def test_your_name_for_someone_finds_their_chat(self):
        self.db.execute("INSERT INTO contacts(peer_id,name,nickname) VALUES ('ali','Ali','Bhai Jaan')")
        hit=self.search('bhai')
        self.assertEqual([r[0] for r in hit],['ali'])
        self.assertEqual(hit[0][9],'Bhai Jaan')
        self.assertEqual(hit[0][1],'Ali','Their own name stays as stored')

    def test_pinned_chats_come_first_and_archived_are_still_found(self):
        self.db.execute("UPDATE conversations SET last_time=5 WHERE peer_id='ali'")
        self.db.execute("UPDATE conversations SET pinned=10,last_time=1 WHERE peer_id='amna'")
        self.assertEqual([r[0] for r in self.search('a')],['amna','ali'])
        self.db.execute("UPDATE conversations SET archived=1 WHERE peer_id='ali'")
        self.assertEqual(self.search('ali')[0][8],1,'A search still finds an archived chat, marked archived')

if __name__ == '__main__': unittest.main()
