"""Read-only reachability check. Never signs up, sends messages or prints tokens."""
import base64, hashlib, json, os, re, socket, ssl, struct, urllib.request, urllib.parse
from pathlib import Path
url = re.search(r'const val SERVER = "([^"]+)"', Path(__file__).resolve().parents[1].joinpath('app/src/main/java/com/irfan/rabta/Config.kt').read_text()).group(1)
print('Checking configured server:', url)
try:
    with urllib.request.urlopen(url+'/health', timeout=90) as r:
        health=json.load(r)
        print('Health:', r.status, 'ok=',health.get('ok'), 'persistent_database=',health.get('db'), 'keys=',sorted(health))
except Exception as e:
    print('Health check failed:', type(e).__name__, str(e)[:180])
try:
    target=urllib.parse.urlparse(url)
    with socket.create_connection((target.hostname,443),timeout=25) as raw:
        with ssl.create_default_context().wrap_socket(raw,server_hostname=target.hostname) as conn:
            conn.settimeout(25)
            key=base64.b64encode(os.urandom(16)).decode()
            conn.sendall(('GET /ws HTTP/1.1\r\nHost: '+target.hostname+'\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: '+key+'\r\n\r\n').encode())
            stream=conn.makefile('rb');line=stream.readline().decode().strip();headers={}
            while True:
                h=stream.readline()
                if h in (b'\r\n',b'\n',b''):break
                k,v=h.decode().split(':',1);headers[k.lower()]=v.strip()
            print('WebSocket handshake:',line)
            if ' 101 ' in line:
                expected=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                assert headers.get('sec-websocket-accept')==expected
                frame=stream.read(2)
                if len(frame)==2 and frame[0]&15==8:
                    payload=stream.read(frame[1]&127)
                    print('Unauthenticated socket rejected with close code:',struct.unpack('!H',payload[:2])[0])
                elif len(frame)==2:
                    opcode=frame[0]&15;size=frame[1]&127
                    if size==126:size=struct.unpack('!H',stream.read(2))[0]
                    if size==127:size=struct.unpack('!Q',stream.read(8))[0]
                    print('First unauthenticated frame opcode:',opcode,'bytes:',size)
                    if opcode==1 and size<=4096:
                        message=json.loads(stream.read(size))
                        print('First message type:',message.get('type'),'keys:',sorted(message))
                else:print('Socket ended before an authentication frame')
except Exception as e:
    print('WebSocket check failed:',type(e).__name__,str(e)[:180])
