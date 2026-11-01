#!/usr/bin/env python3
"""Station request simulator for a Mac connected to AliceBridge. Python 3, stdlib only."""
import argparse,datetime,http.client,ipaddress,json,pathlib,secrets,socket,ssl,struct,subprocess,sys,time,urllib.parse,uuid,hashlib

TARGETS=('scbh.yandex.net','quasar.yandex.net','quasar.yandex.ru')
RCODES={0:'NOERROR',1:'FORMERR',2:'SERVFAIL',3:'NXDOMAIN',4:'NOTIMP',5:'REFUSED'}

BIND_INTERFACE=None

def bind_interface(sock):
    if BIND_INTERFACE and sys.platform=='darwin':sock.setsockopt(socket.IPPROTO_IP,25,socket.if_nametoindex(BIND_INTERFACE))

def connect(address,timeout):
    sock=socket.socket(socket.AF_INET,socket.SOCK_STREAM);sock.settimeout(timeout)
    try:bind_interface(sock);sock.connect(address);return sock
    except BaseException:sock.close();raise

def query(name,qtype=1):
    ident=secrets.randbelow(65536)
    labels=name.rstrip('.').encode('idna').split(b'.')
    if any(not x or len(x)>63 for x in labels):raise ValueError('Invalid DNS name')
    return struct.pack('!6H',ident,0x100,1,0,0,0)+b''.join(bytes([len(x)])+x for x in labels)+b'\0'+struct.pack('!HH',qtype,1)

def dns_name(data,offset):
    labels=[];end=None;seen=set()
    while True:
        if offset>=len(data) or offset in seen:raise ValueError('Invalid DNS name/pointer')
        seen.add(offset);n=data[offset];offset+=1
        if n&0xc0==0xc0:
            if offset>=len(data):raise ValueError('Truncated DNS pointer')
            if end is None:end=offset+1
            offset=((n&63)<<8)|data[offset];continue
        if n&0xc0 or n>63 or offset+n>len(data):raise ValueError('Invalid DNS label')
        if not n:return '.'.join(labels),end if end is not None else offset
        labels.append(data[offset:offset+n].decode('ascii',errors='replace'));offset+=n

def dns_parse(data,request):
    if len(data)<12:raise ValueError('Truncated DNS header')
    ident,flags,qd,an,ns,ar=struct.unpack('!6H',data[:12])
    if data[:2]!=request[:2] or not flags&0x8000 or qd!=1:raise ValueError('DNS ID, QR or question count mismatch')
    wanted,off=dns_name(request,12);qname,pos=dns_name(data,12)
    if qname.lower()!=wanted.lower() or data[pos:pos+4]!=request[off:off+4]:raise ValueError('DNS question mismatch')
    pos+=4;answers=[]
    for _ in range(an):
        name,pos=dns_name(data,pos)
        if pos+10>len(data):raise ValueError('Truncated DNS record')
        kind,clazz,ttl,length=struct.unpack('!HHIH',data[pos:pos+10]);pos+=10;end=pos+length
        if end>len(data):raise ValueError('Truncated DNS RDATA')
        if kind==1 and length==4:value=socket.inet_ntop(socket.AF_INET,data[pos:end])
        elif kind==28 and length==16:value=socket.inet_ntop(socket.AF_INET6,data[pos:end])
        elif kind==5:value=dns_name(data,pos)[0]
        else:value=data[pos:end].hex()
        answers.append({'name':name,'type':kind,'ttl':ttl,'value':value});pos=end
    return {'rcode':RCODES.get(flags&15,str(flags&15)),'truncated':bool(flags&0x200),'answers':answers,'addresses':[a['value'] for a in answers if a['type']==1]}

def read_exact(sock,n):
    out=bytearray()
    while len(out)<n:
        x=sock.recv(n-len(out))
        if not x:raise EOFError('Connection closed before DNS frame completed')
        out.extend(x)
    return bytes(out)

def dns_probe(server,name,tcp,timeout,qtype=1,port=53):
    packet=query(name,qtype)
    if tcp:
        with connect((server,port),timeout) as s:
            s.settimeout(timeout);s.sendall(struct.pack('!H',len(packet))+packet);reply=read_exact(s,struct.unpack('!H',read_exact(s,2))[0])
    else:
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
            s.settimeout(timeout);bind_interface(s);s.connect((server,port));s.send(packet);reply=s.recv(65535)
    return dns_parse(reply,packet)

def network_info():
    info={'gateway':'','interface':'','dns':[]}
    try:
        p=subprocess.run(['route','-n','get','default'],capture_output=True,text=True,timeout=4)
        for line in p.stdout.splitlines():
            key,sep,value=line.strip().partition(':')
            if sep and key in ('gateway','interface'):info[key]=value.strip()
        p=subprocess.run(['scutil','--dns'],capture_output=True,text=True,timeout=4)
        for line in p.stdout.splitlines():
            if 'nameserver[' in line:
                address=line.split(':',1)[1].strip()
                if address not in info['dns']:info['dns'].append(address)
    except (OSError,subprocess.TimeoutExpired):pass
    return info

def http_probe(address,host,path,tls,timeout,ca=None,verify=True,headers=None,max_body=65536):
    raw=connect((address,443 if tls else 80),timeout);sock=raw;meta={}
    try:
        if tls:
            context=ssl.create_default_context(cafile=ca) if verify else ssl._create_unverified_context()
            sock=context.wrap_socket(raw,server_hostname=host)
            meta={'tls_verified':verify,'tls_version':sock.version(),'peer_sha256':hashlib.sha256(sock.getpeercert(binary_form=True)).hexdigest()}
        h={'Host':host,'User-Agent':ARGS.model+'/'+ARGS.version,'Accept':'application/json','Connection':'close'};h.update(headers or {})
        wire='GET '+path+' HTTP/1.1\r\n'+''.join(k+': '+v+'\r\n' for k,v in h.items())+'\r\n'
        if any('\r' in str(v) or '\n' in str(v) for v in h.values()) or '\r' in path or '\n' in path:raise ValueError('Invalid HTTP header/path')
        sock.sendall(wire.encode('ascii'));response=http.client.HTTPResponse(sock);response.begin();body=response.read(max_body+1)
        result={'status':response.status,'reason':response.reason,'headers':{k:v for k,v in response.getheaders() if k.lower() not in ('set-cookie','authorization')},'body_truncated':len(body)>max_body,'body':body[:max_body].decode('utf-8',errors='replace'),**meta}
        try:result['json']=json.loads(result['body'])
        except ValueError:pass
        return result
    finally:
        sock.close()
        if sock is not raw:raw.close()

class Run:
    def __init__(self,args):
        self.args=args;self.records=[];self.answers={};self.network=network_info();self.dns=args.dns or self.network['gateway']
        ipaddress.IPv4Address(self.dns);ipaddress.IPv4Address(args.backend)
    def check(self,name,operation,validate=lambda x:True):
        started=time.monotonic();entry={'name':name,'time':datetime.datetime.now().astimezone().isoformat()}
        try:
            value=operation();entry['result']=value;entry['ok']=bool(validate(value));entry['ms']=round((time.monotonic()-started)*1000)
            if isinstance(value,dict):summary=value.get('rcode') or ('HTTP '+str(value['status']) if 'status' in value else '')
            else:summary=str(value)
            if isinstance(value,dict) and value.get('addresses'):summary+=' · '+', '.join(value['addresses'])
            print(('OK  ' if entry['ok'] else 'FAIL')+' '+name+' · '+summary+' · '+str(entry['ms'])+' мс',flush=True)
            if isinstance(value,dict) and 'json' in value:print(json.dumps(value['json'],ensure_ascii=False,indent=2)[:10000],flush=True)
        except Exception as error:
            value=None;entry.update(ok=False,error=type(error).__name__+': '+str(error));print('FAIL '+name+' · '+entry['error'],flush=True)
        self.records.append(entry);return value
    def http(self,host,path,tls=False,expect=200):
        ips=self.answers.get(host,[])
        if self.args.backend not in ips:
            print('SKIP '+host+path+' · DNS не вернул наш сервер; запрос в Яндекс не отправляем',flush=True);self.records.append({'name':host+path,'ok':False,'skipped':'DNS did not return backend'});return
        label=('HTTPS' if tls else 'HTTP')+' '+host+path.split('?')[0]
        value=self.check(label,lambda:http_probe(self.args.backend,host,path,tls,self.args.timeout,self.args.ca),lambda x:x['status']==expect)
        if tls and value is None:
            value=self.check(label+' · транспорт без проверки сертификата',lambda:http_probe(self.args.backend,host,path,True,self.args.timeout,verify=False),lambda x:x['status']==expect)
            if value:print('    Доставка HTTPS работает. Доверие сертификату НЕ подтверждено.',flush=True)
        return value
    def run(self):
        print('\nAliceBridge · симулятор сетевых запросов станции',flush=True)
        print('Интерфейс: '+self.network['interface']+' · шлюз: '+self.network['gateway']+' · DNS: '+self.dns+' · сервер: '+self.args.backend,flush=True)
        if BIND_INTERFACE:print('Тестовые сокеты привязаны к '+BIND_INTERFACE+' (macOS IP_BOUND_IF).',flush=True)
        print('Модель: '+self.args.model+' · версия: '+self.args.version+' · ID: '+self.args.device_id,flush=True)
        for host in TARGETS:
            for tcp in (False,True):
                value=self.check('DNS '+('TCP' if tcp else 'UDP')+' '+host+' @'+self.dns,lambda h=host,t=tcp:dns_probe(self.dns,h,t,self.args.timeout),lambda x:x['rcode']=='NOERROR' and self.args.backend in x['addresses'])
                if value and value['rcode']=='NOERROR' and self.args.backend in value['addresses']:self.answers[host]=value['addresses']
        self.check('DNS AAAA quasar.yandex.net',lambda:dns_probe(self.dns,'quasar.yandex.net',False,self.args.timeout,28),lambda x:x['rcode']=='NOERROR' and not any(a['type']==28 for a in x['answers']))
        for server in dict.fromkeys((self.args.backend,'1.1.1.1')):
            if server==self.dns:continue
            for tcp in (False,True):self.check('Прямой DNS '+('TCP' if tcp else 'UDP')+' @'+server,lambda s=server,t=tcp:dns_probe(s,'quasar.yandex.net',t,self.args.timeout),lambda x:x['rcode']=='NOERROR' and self.args.backend in x['addresses'])
        self.check('Обычный DNS example.com',lambda:dns_probe(self.dns,'example.com',False,self.args.timeout),lambda x:x['rcode']=='NOERROR' and bool(x['addresses']) and self.args.backend not in x['addresses'])
        self.http('scbh.yandex.net','/generate_204',expect=204)
        self.http('scbh.yandex.net','/generate_204',tls=True,expect=204)
        path='/check_updates?'+urllib.parse.urlencode({'device_id':self.args.device_id,'platform':self.args.model,'version':self.args.version})
        manifest=self.http('quasar.yandex.net',path,tls=True)
        if manifest and 'json' in manifest:
            data=manifest['json'];valid=isinstance(data,dict) and isinstance(data.get('hasUpdate'),bool)
            self.records.append({'name':'Формат check_updates','ok':valid});print(('OK  ' if valid else 'FAIL')+' Формат check_updates',flush=True)
            if self.args.firmware_range and valid and data.get('hasUpdate'):
                url=urllib.parse.urlsplit(data.get('downloadUrl',''))
                if url.hostname in TARGETS or url.hostname==self.args.backend:
                    host=url.hostname if url.hostname in TARGETS else 'quasar.yandex.net';p=url.path+('?' +url.query if url.query else '')
                    self.check('Первые 4096 байт прошивки',lambda:http_probe(self.args.backend,host,p,url.scheme=='https',self.args.timeout,verify=False,headers={'Range':'bytes=0-4095'},max_body=4096),lambda x:x['status']==206 and x['headers'].get('Content-Range',x['headers'].get('content-range','')).startswith('bytes 0-'))
                else:print('SKIP Range · downloadUrl указывает на другой хост',flush=True)
        if self.args.include_sync:self.http('quasar.yandex.net','/get_sync_info?'+urllib.parse.urlencode({'device_id':self.args.device_id,'platform':self.args.model,'version':self.args.version}),tls=True)
        if self.args.internet:
            value=self.check('DNS для обычного HTTPS',lambda:dns_probe(self.dns,'example.com',False,self.args.timeout),lambda x:bool(x['addresses']))
            if value and value['addresses']:self.check('Обычный интернет HTTPS example.com',lambda:http_probe(value['addresses'][0],'example.com','/',True,self.args.timeout),lambda x:x['status']==200)
        report={'time':datetime.datetime.now().astimezone().isoformat(),'network':self.network,'dns':self.dns,'backend':self.args.backend,'model':self.args.model,'version':self.args.version,'device_id':self.args.device_id,'checks':self.records}
        self.args.output.mkdir(parents=True,exist_ok=True)
        path=self.args.output/('station-simulator-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.json');path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)
        failures=sum(not x['ok'] for x in self.records);print('\nИтог: '+str(len(self.records)-failures)+' OK · '+str(failures)+' FAIL\nОтчёт: '+str(path.resolve()),flush=True)
        print('ADB симулятор не предоставляет: ожидание ADB в приложении для Mac ожидаемо.',flush=True)
        return failures==0

def main():
    global ARGS,BIND_INTERFACE
    p=argparse.ArgumentParser(description='Запускать на Mac после подключения к хотспоту AliceBridge. Запросы к Яндексу выполняются только если DNS вернул заданный backend. Прошивка не устанавливается.')
    p.add_argument('--interface',help='Привязать тестовые сокеты к Wi-Fi интерфейсу; по умолчанию интерфейс основного маршрута')
    p.add_argument('--dns',help='DNS DHCP хотспота; по умолчанию шлюз Mac');p.add_argument('--backend',default='78.24.223.83');p.add_argument('--model',default='yandexmini');p.add_argument('--version',default='1.305.4.16.3237824040.20250721');p.add_argument('--device-id',default='simulator-'+uuid.uuid4().hex[:16]);p.add_argument('--timeout',type=float,default=5);p.add_argument('--ca',help='Корневой CA для проверки сертификата backend');p.add_argument('--firmware-range',action='store_true',help='Проверить только первые 4096 байт предложенной прошивки');p.add_argument('--include-sync',action='store_true',help='Получить и вывести get_sync_info без выполнения содержимого');p.add_argument('--internet',action='store_true',help='Дополнительно проверить обычный HTTPS example.com');p.add_argument('--watch',action='store_true');p.add_argument('--interval',type=float,default=15);p.add_argument('--output',type=pathlib.Path,default=pathlib.Path.cwd()/'diagnostics'/'station-simulator')
    ARGS=p.parse_args()
    BIND_INTERFACE=ARGS.interface or network_info()["interface"]
    if not ARGS.ca:
        ca=pathlib.Path(__file__).with_name('station-simulator-ca.pem')
        if ca.is_file():ARGS.ca=str(ca)
    if ARGS.timeout<=0 or ARGS.interval<1:p.error('timeout > 0, interval >= 1')
    if not ARGS.model.replace('_','').isalnum() or any(c in ARGS.version for c in '\r\n'):p.error('Недопустимая модель или версия')
    try:
        while True:
            okay=Run(ARGS).run()
            if not ARGS.watch:return 0 if okay else 1
            time.sleep(ARGS.interval)
    except KeyboardInterrupt:return 130
    except (ValueError,OSError) as e:print('Ошибка: '+str(e)+'. Укажите --dns IP_ШЛЮЗА_ТЕЛЕФОНА.',file=sys.stderr);return 2

if __name__=='__main__':sys.exit(main())
