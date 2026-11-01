"""Real UDP/TCP fixture plus malformed DNS responses; no external network requests."""
import importlib.util,pathlib,struct,socket,threading,unittest
p=pathlib.Path(__file__).resolve().parents[1]/'tools/station_simulator.py'
spec=importlib.util.spec_from_file_location('simulator',p);sim=importlib.util.module_from_spec(spec);spec.loader.exec_module(sim)
def response(q):return q[:2]+struct.pack('!5H',0x8180,1,1,0,0)+q[12:]+b'\xc0\x0c'+struct.pack('!HHIH',1,1,30,4)+bytes([78,24,223,83])
class Test(unittest.TestCase):
 def test_compression_and_transaction(self):
  q=sim.query('quasar.yandex.net');r=response(q);x=sim.dns_parse(r,q);self.assertEqual(x['addresses'],['78.24.223.83']);self.assertEqual(x['rcode'],'NOERROR')
  with self.assertRaises(ValueError):sim.dns_parse(bytes([r[0]^1])+r[1:],q)
  with self.assertRaises(ValueError):sim.dns_parse(r[:-1],q)
 def test_pointer_loop(self):
  with self.assertRaises(ValueError):sim.dns_name(b'\xc0\x00',0)
 def test_udp_tcp(self):
  for tcp in (False,True):
   with socket.socket(socket.AF_INET,socket.SOCK_STREAM if tcp else socket.SOCK_DGRAM) as server:
    server.bind(('127.0.0.1',0));server.settimeout(3)
    if tcp:server.listen(1)
    def serve():
     if tcp:
      with server.accept()[0] as conn:
       q=sim.read_exact(conn,struct.unpack('!H',sim.read_exact(conn,2))[0]);r=response(q);conn.sendall(struct.pack('!H',len(r))+r)
     else:
      q,peer=server.recvfrom(4096);server.sendto(response(q),peer)
    t=threading.Thread(target=serve);t.start();x=sim.dns_probe('127.0.0.1','quasar.yandex.net',tcp,2,port=server.getsockname()[1]);t.join(3);self.assertEqual(x['addresses'],['78.24.223.83'])
if __name__=='__main__':unittest.main()
