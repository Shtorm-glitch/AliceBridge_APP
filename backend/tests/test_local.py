import contextlib
import http.client
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import zlib
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
import control
import equasar_server as server

class LocalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ctrl = control.Control(self.tmp.name)
        self.ctrl.enabled = True
        self.prepare_patch=patch.object(self.ctrl,'prepare');self.prepare_patch.start()
        self.saved = server.CONTROL; server.CONTROL = self.ctrl
        self.web = server.ReusableServer(('127.0.0.1', 0), server.EquasarHandler)
        self.thread = threading.Thread(target=self.web.serve_forever, daemon=True); self.thread.start()
    def tearDown(self):
        self.web.shutdown(); self.web.server_close(); server.CONTROL = self.saved; self.tmp.cleanup();self.prepare_patch.stop()
    def call(self, path, method='GET', headers=None, body=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.web.server_port, timeout=3)
        conn.request(method, path, body=body, headers=headers or {})
        r = conn.getresponse(); result = r.status, dict(r.getheaders()), r.read(); conn.close(); return result
    def firmware(self, platform, version):
        record = self.ctrl.record(platform, version); record.parent.mkdir(parents=True, exist_ok=True)
        data = bytes(range(256)) * 4; (record.parent / 'update.zip').write_bytes(data)
        record.write_text(json.dumps(dict(platform=platform, version=version, size=len(data),
            crc32=zlib.crc32(data), metadata=dict(version=version, crc32=zlib.crc32(data), updateId='123',
            fallbackIp='93.158.134.158', downloadUrl='https://yandex.example/should-not-be-used'))))
        self.ctrl.select(platform, version, True); return data
    def test_max_numeric_version_and_installed_build(self):
        self.assertTrue(control.allowed_firmware('5000000921'))
        self.assertFalse(control.allowed_firmware('5000000896'))
        self.assertFalse(control.allowed_firmware('5000000939'))
        self.firmware('yandexstation_2','5000000921')
        self.assertIsNone(self.ctrl.update_for('yandexstation_2','PPR1.180610.011.5000000921'))
        self.assertIsNone(self.ctrl.update_for('yandexstation_2','5000000921'))
        result=self.ctrl.update_for('yandexstation_2','5000000939')
        self.assertEqual(result['version'],'5000000921')
        self.assertIn('/yandexstation_2/ota/5000000921.zip',result['downloadUrl'])
    def test_allow_update_uses_station1_checkbox_without_query(self):
        version='2.369.5.46.4778391386.20261013.52'
        self.firmware('yandexstation',version)
        self.ctrl.dns_status('a'*32,'127.0.0.1','yandexstation')
        status,_,body=self.call('/allow_update')
        self.assertEqual(status,200)
        self.assertEqual(json.loads(body)['version'],version)
        self.assertTrue(json.loads(body)['hasUpdate'])
    def test_allow_update_known_device_without_checkbox_and_already_installed(self):
        version='2.369.5.46.4778391386.20261013.52'
        self.firmware('yandexstation',version)
        self.call('/check_updates?device_id=station1&platform=yandexstation&version=old')
        self.assertEqual(json.loads(self.call('/allow_update')[2])['version'],version)
        self.call('/check_updates?device_id=station1&platform=yandexstation&version='+version)
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
    def test_allow_update_rejects_missing_stale_or_conflicting_context(self):
        self.firmware('yandexstation','2.369.5.46.4778391386.20261013.52')
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
        self.ctrl.dns_status('a'*32,'127.0.0.1','yandexstation')
        self.ctrl.sessions['a'*32]['seen']-=121
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
        self.ctrl.dns_status('a'*32,'127.0.0.1','yandexstation')
        self.call('/check_updates?device_id=mini&platform=yandexmini&version=factory')
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
    def test_allow_update_does_not_mix_devices_or_app_sessions(self):
        self.firmware('yandexstation','2.369.5.46.4778391386.20261013.52')
        self.ctrl.dns_status('a'*32,'127.0.0.1','yandexstation')
        self.ctrl.dns_status('b'*32,'127.0.0.1','yandexstation')
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
        self.ctrl.sessions.clear()
        self.call('/check_updates?device_id=one&platform=yandexstation&version=old')
        self.call('/check_updates?device_id=two&platform=yandexmini&version=factory')
        self.assertEqual(json.loads(self.call('/allow_update')[2]),{'hasUpdate':False})
        self.assertEqual(json.loads(self.call('/allow_update?device_id=unrelated')[2]),{'hasUpdate':False})
    def test_feedback_refuses_two_active_stations_even_with_single_app(self):
        for identifier in ['one', 'two']:
            self.ctrl.request('10.0.0.2','GET','/check_updates?device_id='+identifier+'&platform=yandexmini&version=factory',{})
        result=self.ctrl.dns_status('a'*32,'10.0.0.2')
        self.assertEqual(result['status'],'ambiguous')
        self.assertNotIn('ota',result)

    def test_stale_feedback_is_missing_update_evidence_not_no_traffic(self):
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        self.ctrl.devices['one']['seen']-=181
        for observed in self.ctrl.http_activity.values(): observed['time']-=181
        self.ctrl.devices['one']['version_confirmed']='historical'
        self.ctrl.devices['one']['ota']={'sent':100,'size':100}
        result=self.ctrl.dns_status('a'*32,'10.0.0.2')
        self.assertEqual(result['status'],'stale')
        self.assertIn('данных об обновлении',result['message'])
        self.assertIn('DNS-запросы могут продолжаться',result['message'])
        for field in ('ota','confirmed','check_event_id','version'):
            self.assertNotIn(field,result)

    def test_phone_dns_and_status_polls_never_refresh_station(self):
        ip='10.0.0.9'
        self.ctrl.request(ip,'GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        self.ctrl.devices['one']['seen']-=181
        for observed in self.ctrl.http_activity.values(): observed['time']-=181
        seen=self.ctrl.devices['one']['seen']
        names=['quasar.yandex.net','status.'+'a'*32+'.alicebridge.invalid']
        for name in names:
            for qtype in (1,28,16):
                qname=b''.join(bytes([len(p)])+p.encode() for p in name.split('.'))+b'\x00'
                query=b'\x12\x34\x01\x00\x00\x01'+b'\x00'*6+qname+qtype.to_bytes(2,'big')+b'\x00\x01'
                with patch.object(server,'_dns_forward',return_value=None):
                    server._dns_handle(query,ip)
        self.assertEqual(self.ctrl.devices['one']['seen'],seen)
        self.assertEqual(self.ctrl.dns_status('a'*32,ip)['status'],'stale')

    def test_anonymous_http_does_not_promote_historical_station(self):
        ip='10.0.0.2'
        self.ctrl.request(ip,'GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        self.ctrl.devices['one']['seen']-=181
        for observed in self.ctrl.http_activity.values(): observed['time']-=181
        self.ctrl.request(ip,'GET','/generate_204',{})
        result=self.ctrl.dns_status('a'*32,ip)
        self.assertEqual(result['status'],'network_activity')
        self.assertNotIn('ota',result)

    def test_long_stream_does_not_claim_silence_or_confirm_installation(self):
        ip='10.0.0.2'
        self.ctrl.request(ip,'GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        self.ctrl.begin_transfer(ip,'yandexmini','target',0,1000)
        self.ctrl.devices['one']['seen']-=181
        for observed in self.ctrl.http_activity.values(): observed['time']-=181
        self.ctrl.transfer(ip,'yandexmini','target',0,499,1000)
        result=self.ctrl.dns_status('a'*32,ip)
        self.assertEqual(result['status'],'network_activity')
        self.assertNotIn('Свежих запросов колонки нет',result['message'])
        self.assertNotIn('давно не выходила',result['message'])
        self.assertNotIn('ota',result)
        self.assertNotIn('confirmed',result)

    def test_all_identified_methods_refresh_activity_without_restarting_update_phase(self):
        ip='10.0.0.22'
        self.ctrl.request(ip,'GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        revision=self.ctrl.devices['one']['check_event_id']
        for method in ['GET','POST','HEAD','PUT','PATCH','DELETE']:
            self.ctrl.devices['one']['seen']-=181
            request=self.ctrl.request(ip,method,'/arbitrary_service?device_id=one',{})
            self.ctrl.response(request,ip,202,'/arbitrary_service?device_id=one')
            result=self.ctrl.dns_status('a'*32,ip)
            self.assertEqual(result['status'],'device')
            self.assertEqual(result['activity']['method'],method)
            self.assertEqual(result['activity']['http'],202)
            self.assertEqual(result['check_event_id'],revision)

    def test_unidentified_activity_is_generic_and_never_exposes_urls_or_firmware(self):
        ip='10.0.0.23'
        for method in ['GET','POST','HEAD','PUT','PATCH','DELETE']:
            request=self.ctrl.request(ip,method,'/private_other_person?password=secret-value',{})
            self.ctrl.response(request,ip,403,'/private_other_person?password=secret-value')
            result=self.ctrl.dns_status('a'*32,ip)
            self.assertEqual(result['status'],'network_activity')
            text=json.dumps(result)
            self.assertNotIn('private_other_person',text)
            self.assertNotIn('secret-value',text)
            self.assertNotIn('ota',result)
            self.assertNotIn('version',result)
            self.assertEqual(result['activity']['attribution'],'source_ip')
        self.assertEqual(self.ctrl.dns_status('b'*32,'10.0.0.24')['status'],'ready')

    def test_unrelated_response_does_not_replace_identified_station_response(self):
        ip='10.0.0.25'
        request=self.ctrl.request(ip,'POST','/state?device_id=one&platform=yandexmini',{})
        self.ctrl.response(request,ip,200,'/state?device_id=one&platform=yandexmini')
        other=self.ctrl.request(ip,'GET','/private_other_person',{})
        self.ctrl.response(other,ip,500,'/private_other_person')
        result=self.ctrl.dns_status('a'*32,ip)
        self.assertEqual(result['http'],200)
        self.assertNotIn('private_other_person',json.dumps(result))

    def test_feedback_check_revision_changes_only_for_update_request(self):
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=one&platform=yandexmini&version=factory',{})
        first=self.ctrl.dns_status('a'*32,'10.0.0.2')
        self.ctrl.request('10.0.0.2','GET','/get_sync_info?device_id=one',{})
        second=self.ctrl.dns_status('a'*32,'10.0.0.2')
        self.assertEqual(first['check_event_id'],second['check_event_id'])
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=one&version=factory',{})
        third=self.ctrl.dns_status('a'*32,'10.0.0.2')
        self.assertGreater(third['check_event_id'],second['check_event_id'])
        self.assertEqual(third['server_boot'],first['server_boot'])

    def test_new_non_target_version_invalidates_old_confirmation(self):
        self.ctrl.selections['yandexmini']={'version':'target'}
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=one&platform=yandexmini&version=target',{})
        self.assertEqual(self.ctrl.devices['one']['version_confirmed'],'target')
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=one&version=factory',{})
        self.assertNotIn('version_confirmed',self.ctrl.devices['one'])

    def test_dns_tcp_status_handles_fragmented_frames_and_unicode_txt_chunks(self):
        payload={'feedback_schema':2,'status':'device','message':'Проверка состояния ' * 45,'check_event_id':72,'server_boot':123}
        domain=b'status.' + b'a'*32 + b'.alicebridge.invalid'
        question=b''.join(bytes([len(part)])+part for part in domain.split(b'.'))+b'\x00\x00\x10\x00\x01'
        query=b'\x12\x34\x01\x00\x00\x01'+b'\x00'*6+question
        wire=len(query).to_bytes(2,'big')+query
        class FragmentedSocket:
            def __init__(self):self.data=wire*2;self.reply=b'';self.closed=False
            def recv(self,n):
                part=self.data[:1];self.data=self.data[1:];return part
            def sendall(self,b):self.reply+=b
            def settimeout(self,n):pass
            def getpeername(self):return ('10.0.0.2',12345)
            def close(self):self.closed=True
        sock=FragmentedSocket()
        with patch.object(self.ctrl,'dns_status',return_value=payload):server._dns_tcp_conn(sock)
        self.assertTrue(sock.closed)
        wire=sock.reply
        for _ in range(2):
            n=int.from_bytes(wire[:2],'big');reply=wire[2:2+n];wire=wire[2+n:]
            self.assertEqual(reply[:2],query[:2]);self.assertEqual(reply[3]&15,0)
            offset=12+len(question)+12
            data=reply[offset:];decoded=b''
            while data:
                size=data[0];decoded+=data[1:1+size];data=data[1+size:]
            self.assertEqual(json.loads(decoded),payload)
        self.assertFalse(wire)

    def test_proxy_client_ip_requires_explicit_trust(self):
        headers={'X-Real-IP':'198.51.100.9'}
        with patch.dict(server.os.environ, {'TRUST_LOOPBACK_PROXY':'0'}):
            self.call('/generate_204',headers=headers)
            self.assertEqual(self.ctrl.events[-2]['ip'],'127.0.0.1')
        with patch.dict(server.os.environ, {'TRUST_LOOPBACK_PROXY':'1'}):
            self.call('/generate_204',headers=headers)
            self.assertEqual(self.ctrl.events[-2]['ip'],'198.51.100.9')
            self.call('/generate_204',headers={'X-Real-IP':'invalid'})
            self.assertEqual(self.ctrl.events[-2]['ip'],'127.0.0.1')

    def test_cutoff_and_nearest_target(self):
        versions=['1.369.20261022','1.368.20261021','1.367.20261020','1.370.20261023']
        self.assertEqual(control.nearest_firmware(versions),'1.368.20261021')
        self.assertEqual(control.nearest_firmware(['1.3.20261019','1.4.20261020']),'1.4.20261020')
        self.assertIsNone(control.nearest_firmware(['1.3.20261022']))
        self.assertTrue(control.needs_downgrade('1.3.20261022'))
        self.assertFalse(control.needs_downgrade('1.3.20261021'))
        self.assertFalse(control.needs_downgrade('1.3.20261020'))
        self.assertFalse(control.needs_downgrade('unknown'))
        with self.assertRaises(ValueError):self.ctrl.select('yandexmini','1.3.20261022',True)
    def test_dns_feedback_sessions_are_not_mixed(self):
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=abc&platform=yandexmini&version=1.3.20261022',{})
        self.assertEqual(self.ctrl.dns_status('a'*32,'10.0.0.2')['platform'],'yandexmini')
        self.assertEqual(self.ctrl.dns_status('b'*32,'10.0.0.2')['status'],'ambiguous')
        self.assertEqual(self.ctrl.dns_status('c'*32,'10.0.0.3')['status'],'ready')
    def test_explicit_station1_hint_binds_only_one_stable_device(self):
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=unknown-station&version=1.3.7.0.352953201.20181225.factory',{})
        result=self.ctrl.dns_status('a'*32,'10.0.0.2','yandexstation')
        self.assertEqual(result['platform'],'yandexstation')
        self.assertEqual(self.ctrl.resolve_platform({'device_id':['unknown-station']}),'yandexstation')
        self.assertEqual(self.ctrl.resolve_platform({'device_id':['unrelated']}),'')
        self.assertEqual(self.ctrl.dns_status('a'*32,'10.0.0.2','evil')['status'],'invalid_model_hint')
    def test_explicit_station1_hint_never_overrides_another_model(self):
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=mini&platform=yandexmini&version=factory',{})
        self.assertEqual(self.ctrl.dns_status('a'*32,'10.0.0.2','yandexstation')['status'],'model_mismatch')
        self.assertEqual(self.ctrl.resolve_platform({'device_id':['mini']}),'yandexmini')
    def test_explicit_station1_hint_refuses_multiple_devices(self):
        for identifier in ['first','second']:
            self.ctrl.request('10.0.0.2','GET','/check_updates?device_id='+identifier+'&version=factory',{})
        self.assertEqual(self.ctrl.dns_status('a'*32,'10.0.0.2','yandexstation')['status'],'ambiguous')
        self.assertEqual(self.ctrl.resolve_platform({'device_id':['first']}),'')
    def test_observed_lite_factory_gets_selected_retail_image(self):
        self.firmware('yandexmicro','0.372.1.8.4790672876.20261019.300')
        factory='0.52.1.85.922829076.20210318.PVT.factory'
        code,_,body=self.call('/check_updates?device_id=lite&platform=yandexmicro&version='+factory)
        result=json.loads(body)
        self.assertEqual(code,200)
        self.assertTrue(result['hasUpdate'])
        self.assertEqual(result['version'],'0.372.1.8.4790672876.20261019.300')
        self.assertIsNone(self.ctrl.update_for('yandexmicro','0.372.1.8.4790672876.20261019.300'))
        self.assertIsNotNone(self.ctrl.update_for('yandexmicro','0.369.20261020'))
    def test_station1_without_platform_identified_and_remembered_across_restart(self):
        target='2.372.5.8.4790795839.20261019.52'
        factory='1.3.7.0.631346810.20200316.factory'
        self.firmware('yandexstation',target)
        cfg=Path(self.tmp.name)/'config';cfg.mkdir()
        (cfg/'known-firmware-platforms.json').write_text(json.dumps({factory:'yandexstation'}))
        _,_,body=self.call('/check_updates?device_id=station1-test&version='+factory)
        result=json.loads(body)
        self.assertTrue(result['hasUpdate']);self.assertEqual(result['version'],target)
        self.assertEqual(self.ctrl.devices['station1-test']['platform'],'yandexstation')
        restarted=control.Control(self.tmp.name);restarted.enabled=True
        with patch.object(server,'CONTROL',restarted):
            _,_,body=self.call('/check_updates?device_id=station1-test&version='+target)
        self.assertEqual(json.loads(body),{'hasUpdate':False})
        self.assertEqual(restarted.devices['station1-test']['version_confirmed'],target)
        # An unrelated device behind the same public address cannot inherit the model.
        _,_,body=self.call('/check_updates?device_id=other-test&version=unknown.factory')
        self.assertEqual(json.loads(body),{'hasUpdate':False})
        self.assertEqual(self.ctrl.resolve_platform({'device_id':['other-test']}),'')
    def test_registration_firmware_and_head_preserve_device_identity(self):
        current='0.52.1.85.922829076.20210318.PVT.factory'
        self.ctrl.request('10.0.0.2','POST','/register?device_id=lite&platform=yandexmicro&firmware_version='+current,{})
        self.ctrl.request('10.0.0.2','HEAD','/generate_204',{'User-Agent':'YandexStation'})
        devices=self.ctrl.snapshot()['devices']
        self.assertEqual(len(devices),1)
        self.assertEqual(devices['lite']['platform'],'yandexmicro')
        self.assertEqual(devices['lite']['version'],current)
    def test_explicit_ota_pause_does_not_prepare_or_enable(self):
        version='0.372.1.8.4790672876.20261019.300'
        self.firmware('yandexmicro',version)
        self.ctrl.select('yandexmicro',version,False)
        with patch.object(self.ctrl,'prepare',side_effect=AssertionError('OTA paused')):
            code,_,body=self.call('/check_updates?platform=yandexmicro&version=0.52.1.85.922829076.20210318.PVT.factory')
        self.assertEqual(code,200)
        self.assertEqual(json.loads(body),{'hasUpdate':False})
        self.assertFalse(self.ctrl.selections['yandexmicro']['enabled'])
    def test_standby_does_not_activate(self):
        self.ctrl.enabled=False
        self.assertEqual(self.call('/get_sync_info')[0], 503)
    def test_no_upstream_even_for_unknown_paths(self):
        with patch.object(server,'ALLOW_UPSTREAM',False),patch.object(server,'SYNC_PROXY',False),patch.object(server, 'real_ip', side_effect=AssertionError('network forbidden')):
            self.assertEqual(self.call('/generate_204')[0], 204)
            self.assertEqual(self.call('/register', 'POST', body=b'{}')[0], 200)
            code, _, body = self.call('/get_sync_info?device_id=abc&platform=yandexmini')
            self.assertEqual(code,200); self.assertTrue(json.loads(body)['config']['system_config']['adbd']['remoteActivation'])
            self.assertEqual(self.call('/unknown')[0],502)
            self.assertEqual(self.call('/glagol/unknown')[0],502)
    def test_platforms_are_isolated_and_current_version_exact(self):
        self.firmware('yandexmini','1.305.20261021'); self.firmware('yandexmicro','0.369.20261020')
        for p,v in [('yandexmini','1.305.20261021'),('yandexmicro','0.369.20261020')]:
            data=json.loads(self.call('/check_updates?platform='+p+'&version=1.999.20261022')[2])
            self.assertEqual(data['version'], v); self.assertIn('/'+p+'/ota/',data['downloadUrl'])
            self.assertEqual(data['fallbackIp'],'78.24.223.83');self.assertTrue(data['critical'])
            self.assertEqual(json.loads(self.call('/check_updates?platform='+p+'&version='+v)[2]),{'hasUpdate':False})
        self.assertEqual(json.loads(self.call('/check_updates?version=1.999.20261022')[2]),{'hasUpdate':False})
        self.assertEqual(json.loads(self.call('/check_updates?platform=unknown&version=1.999.20261022')[2]),{'hasUpdate':False})
        self.assertIsNone(self.ctrl.update_for('yandexmini','1.305.20261021.suffix'))
    def test_ranges_head_and_full_transfer(self):
        original=self.firmware('yandexmini','1.305.20261021'); url='/yandexmini/ota/1.305.20261021.zip'
        code,h,b=self.call(url,'HEAD'); self.assertEqual((code,len(b),int(h['Content-Length'])),(200,0,1024))
        for value,expected in [('bytes=0-9',original[:10]),('bytes=-10',original[-10:]),('bytes=1000-',original[1000:]),('bytes=1000-9000',original[1000:])]:
            code,_,body=self.call(url,headers={'Range':value});self.assertEqual(code,206);self.assertEqual(body,expected)
        for value in ('bytes=1024-','bytes=10-2','bytes=0-1,3-4','bytes=-0'):
            self.assertEqual(self.call(url,headers={'Range':value})[0],416)
        self.assertEqual(self.call(url)[2],original)
        self.assertEqual(self.call('/yandexmicro/ota/1.305.20261021.zip')[0],404)
    def test_range_overlap_does_not_fake_completion(self):
        self.assertEqual(self.ctrl.transfer('1.2.3.4','yandexmini','1',0,49,100),50)
        self.assertEqual(self.ctrl.transfer('1.2.3.4','yandexmini','1',25,74,100),75)
        self.assertEqual(self.ctrl.transfer('1.2.3.4','yandexmini','1',75,99,100),100)
    def test_device_tracks_dhcp_change_and_version(self):
        self.firmware('yandexmini','1.305.20261021')
        self.ctrl.request('10.0.0.2','GET','/check_updates?device_id=abc&platform=yandexmini&version=old',{})
        self.ctrl.request('10.0.0.3','GET','/check_updates?device_id=abc&platform=yandexmini&version=1.305.20261021',{})
        devices=self.ctrl.snapshot()['devices'];self.assertEqual(len(devices),1);self.assertEqual(devices['abc']['ip'],'10.0.0.3')
        self.assertEqual(devices['abc']['version_confirmed'],'1.305.20261021')
        self.assertTrue(any(e['kind']=='ip_changed' for e in self.ctrl.events))
    def test_wrong_platform_never_serves_zip(self):
        self.firmware('yandexmicro','0.369.20261020')
        self.ctrl.request('127.0.0.1','GET','/check_updates?device_id=abc&platform=yandexmini&version=1',{})
        self.assertEqual(self.call('/yandexmicro/ota/0.369.20261020.zip')[0],409)
    def test_secret_masking_and_path_validation(self):
        self.ctrl.request('10.0.0.2','GET','/sync?token=secret-value&platform=yandexmini',{})
        self.assertNotIn('secret-value',json.dumps(self.ctrl.snapshot()))
        for value in ('../x','x/y','https://evil','..',''):
            with self.assertRaises(ValueError):control.component(value)
    def test_retail_ca_session_never_offers_or_prepares_firmware(self):
        token='a'*32
        feedback=self.ctrl.dns_status(token,'127.0.0.1','ca-plum')
        self.assertEqual(feedback['status'],'ready')
        self.assertEqual(self.ctrl.retail_ca_mode('127.0.0.1'),'plum')
        with patch.object(self.ctrl,'update_for',side_effect=AssertionError('CA must not select firmware')):
            for path in ['/check_updates?device_id=plum-ca&platform=plum&version=factory','/allow_update']:
                status,_,body=self.call(path)
                self.assertEqual(status,200);self.assertEqual(json.loads(body),{'hasUpdate':False})
        self.assertEqual(self.ctrl.retail_ca_mode('10.0.0.2'),'')
        self.assertEqual(self.call('/plum/ota/0.369.20261013.zip')[0],409)
        restored=control.Control(self.tmp.name);self.assertEqual(restored.retail_ca_mode('127.0.0.1'),'plum')
    def test_retail_ca_mode_expires_and_ambiguous_mode_fails_closed(self):
        self.ctrl.dns_status('b'*32,'10.0.0.2','ca-bergamot')
        self.assertEqual(self.ctrl.retail_ca_mode('10.0.0.2'),'bergamot')
        self.ctrl.dns_status('c'*32,'10.0.0.2','')
        self.assertEqual(self.ctrl.retail_ca_mode('10.0.0.2'),'ambiguous')
        for session in self.ctrl.sessions.values():session['seen']=0
        self.assertEqual(self.ctrl.retail_ca_mode('10.0.0.2'),'')
    def test_retail_ca_blocks_service_domains_only_in_selected_session(self):
        import struct
        name=b'\x08uniproxy\x05alice\x06yandex\x03net\x00'
        query=b'\x12\x34\x01\x00\x00\x01'+b'\x00'*6+name+b'\x00\x01\x00\x01'
        self.ctrl.dns_status('d'*32,'10.0.0.2','ca-bergamot')
        response=server._dns_handle(query,'10.0.0.2');self.assertEqual(response[-4:],bytes([127,0,0,1]))
        with patch.object(server,'_dns_forward',return_value=None) as forward:
            server._dns_handle(query,'10.0.0.3');forward.assert_called_once()
    def test_dns_reply_status_and_timeout(self):
        query=b'\x12\x34\x01\x00\x00\x01'+b'\x00'*6+b'\x06quasar\x06yandex\x03net\x00\x00\x01\x00\x01'
        response=server._dns_handle(query,'10.0.0.2');self.assertEqual(response[:2],query[:2]);self.assertEqual(response[3]&15,0)
        self.assertEqual(self.ctrl.events[-1]['status'],'NOERROR')
        self.ctrl.enabled=False
        with patch.object(server,'_dns_forward',return_value=None):
            self.assertEqual(server._dns_handle(query)[3]&15,2)
    def test_download_rejects_corrupt_image(self):
        import zipfile
        payload=io.BytesIO()
        with zipfile.ZipFile(payload,'w') as z:z.writestr('test','test')
        data=payload.getvalue()
        class Response(io.BytesIO):
            headers={'Content-Length':str(len(data))}
            def geturl(self):return control.MIRROR+'/yandexmini/1/update.zip'
        with patch('firmware_fetch.request',side_effect=[Response(json.dumps({'version':'1.20261021','crc32':1}).encode()),Response(data),Response(data)]):
            self.ctrl.download('yandexmini','1.20261021')
            for _ in range(100):
                if self.ctrl.downloads['yandexmini/1.20261021']['status']!='loading':break
                threading.Event().wait(.01)
        self.assertEqual(self.ctrl.downloads['yandexmini/1.20261021']['status'],'error');self.assertIsNone(self.ctrl.verified('yandexmini','1.20261021'))

    def test_factory_suffixes_and_older_retail_match_upstream(self):
        self.firmware('cucumber','0.306.1.12.3232763286.20250718.97')
        for version in ('0.7.1.12.2195125507.20240228.factory-7','0.10.1.5.3927784145.20260316.factory-10','0.306.20250717'):
            code,_,body=self.call('/check_updates?platform=cucumber&version='+version)
            self.assertEqual(code,200);self.assertTrue(json.loads(body)['hasUpdate'])
        self.assertFalse(json.loads(self.call('/check_updates?platform=cucumber&version=0.306.1.12.3232763286.20250718')[2])['hasUpdate'])
    def test_real_sync_preserves_native_fields_and_local_fallback(self):
        class Response:status=200
        native={'status':'ok','native_model_field':{'value':17},'config':{'system_config':{'native_feature':True},'device_config':{'name':'original'}}}
        with patch.object(server,'ALLOW_UPSTREAM',True),patch.object(server,'SYNC_PROXY',True),patch.object(server,'real_ip',return_value='203.0.113.1'),patch.object(server.EquasarHandler,'_upstream',return_value=(Response(),json.dumps(native).encode())):
            code,_,body=self.call('/get_sync_info?platform=cucumber');value=json.loads(body)
        self.assertEqual(code,200);self.assertEqual(value['native_model_field'],native['native_model_field']);self.assertEqual(value['config']['device_config']['name'],'original')
        for section in ('system_config','device_config'):self.assertTrue(value['config'][section]['adbd']['remoteActivation'])
        self.assertTrue(value['adbd']['remoteActivation'])
        with patch.object(server,'ALLOW_UPSTREAM',True),patch.object(server,'SYNC_PROXY',True),patch.object(server,'real_ip',return_value='203.0.113.1'),patch.object(server.EquasarHandler,'_upstream',side_effect=TimeoutError('fixture timeout')):
            code,_,body=self.call('/get_sync_info?platform=cucumber')
        self.assertEqual(code,200);self.assertTrue(json.loads(body)['adbd']['remoteActivation'])
    def test_max_sync_overrides_both_tv_debug_switches(self):
        class Response:status=200
        native={'config':{'system_config':{'com.yandex.tv.services.platform':{'adbEnabled':False,'developmentSettingsEnabled':False,'keep_me':17}}},'native_field':'preserved'}
        with patch.object(server,'ALLOW_UPSTREAM',True),patch.object(server,'SYNC_PROXY',True),patch.object(server,'real_ip',return_value='203.0.113.1'),patch.object(server.EquasarHandler,'_upstream',return_value=(Response(),json.dumps(native).encode())):
            code,headers,body=self.call('/get_sync_info?device_id=max-fixture&platform=yandexstation_2')
        result=json.loads(body);system=result['config']['system_config'];tv=system['com.yandex.tv.services.platform']
        self.assertEqual(code,200);self.assertEqual(headers['Cache-Control'],'no-store')
        self.assertTrue(system['adbd']['remoteActivation']);self.assertTrue(system['adbd']['network'])
        self.assertTrue(tv['adbEnabled']);self.assertTrue(tv['developmentSettingsEnabled']);self.assertEqual(tv['keep_me'],17)
        self.assertEqual(result['native_field'],'preserved');self.assertFalse(native['config']['system_config']['com.yandex.tv.services.platform']['adbEnabled'])
    def test_max_sync_local_fallback_and_known_device_binding(self):
        self.ctrl.resolve_platform({'device_id':['max-known'],'platform':['yandexstation_2']})
        with patch.object(server,'ALLOW_UPSTREAM',False),patch.object(server,'SYNC_PROXY',True):
            _,_,body=self.call('/get_sync_info?device_id=max-known')
        system=json.loads(body)['config']['system_config']
        self.assertTrue(system['adbd']['remoteActivation']);self.assertTrue(system['adbd']['network'])
        self.assertEqual(system['com.yandex.tv.services.platform'],{'adbEnabled':True,'developmentSettingsEnabled':True})
    def test_max_tv_switches_do_not_affect_other_models(self):
        value=json.loads(server.sync_payload(source={'config':{'system_config':{}}},platform='yandexmidi'))
        self.assertNotIn('com.yandex.tv.services.platform',value['config']['system_config'])
        self.assertTrue(value['config']['system_config']['adbd']['network'])
    def test_register_always_allvalid_after_forwarding(self):
        class Response:status=403
        with patch.object(server,'ALLOW_UPSTREAM',True),patch.object(server,'real_ip',return_value='203.0.113.1'),patch.object(server.EquasarHandler,'_upstream',return_value=(Response(),b'{"error":"fixture"}')) as upstream:
            code,_,body=self.call('/register?platform=yandexmini','POST',body=b'original-body')
        self.assertEqual(code,200);self.assertEqual(json.loads(body),{'message':'allvalid','status':'ok'});self.assertEqual(upstream.call_args.args[2],b'original-body')
        self.assertTrue(any(e['kind']=='upstream_response' and e['code']==403 for e in self.ctrl.events))
    def test_service_requests_are_forwarded_unchanged(self):
        class Response:
            status=200
            def getheaders(self):return [('Content-Type','application/json')]
            def getheader(self,key):return 'application/json' if key=='Content-Type' else None
        payload=b'{"native_result":true}'
        for path in ('/set_device_capability','/update_device_state','/push_subscribe','/get_timezone'):
            with patch.object(server,'ALLOW_UPSTREAM',True),patch.object(server,'real_ip',return_value='203.0.113.1'),patch.object(server.EquasarHandler,'_upstream',return_value=(Response(),payload)) as upstream:
                code,_,body=self.call(path,'POST',body=b'original-body')
            self.assertEqual((code,body),(200,payload));self.assertEqual(upstream.call_args.args[2],b'original-body')

    def test_legacy_mini_device_type_selects_exact_platform(self):
        target='1.305.4.16.3237824040.20250721'
        self.firmware('yandexmini',target)
        path='/check_updates?device_id=legacy-mini&version=1.3.4.12.532420178.20191021&device_type=yandexmini'
        code,_,body=self.call(path)
        self.assertEqual(code,200)
        self.assertEqual(json.loads(body)['version'],target)
        device=self.ctrl.devices['legacy-mini']
        self.assertEqual(device['platform'],'yandexmini')
        self.assertEqual(device['version'],'1.3.4.12.532420178.20191021')
        self.assertEqual(json.loads(self.call(path+'&platform=unknown')[2]),{'hasUpdate':False})

    def test_full_ota_retry_resets_progress_and_interruption_is_visible(self):
        ip='10.0.0.2';platform='yandexmini_2';version='0.307.20250721'
        self.ctrl.request(ip,'GET','/check_updates?device_id=retry&platform='+platform,{})
        self.ctrl.begin_transfer(ip,platform,version,0,100)
        self.assertEqual(self.ctrl.transfer(ip,platform,version,0,99,100),100)
        self.ctrl.begin_transfer(ip,platform,version,0,100)
        self.assertEqual(self.ctrl.devices['retry']['ota']['sent'],0)
        self.assertEqual(self.ctrl.transfer(ip,platform,version,0,39,100),40)
        self.ctrl.interrupt_transfer(ip,platform,version)
        self.assertIn('прервана',self.ctrl.devices['retry']['ota']['status'])
        self.ctrl.begin_transfer(ip,platform,version,40,100)
        self.assertEqual(self.ctrl.transfer(ip,platform,version,40,99,100),100)

if __name__=='__main__':unittest.main()
