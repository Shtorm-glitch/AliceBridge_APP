import dev.alice.bridge.ProgressInfo;
public final class ProgressInfoTest {
 private static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 private static ProgressInfo data(long sent,long total,String confirmed,String message){return new ProgressInfo("device","yandexmicro",confirmed.isEmpty()?"factory":"target","target",confirmed,message,"","",0,0,sent,total,1);}
 public static void main(String[]args){
  check(ProgressInfo.EMPTY.view(false,false,"").step()==0,"Wait for actual device");
  ProgressInfo partial=data(50,100,"","");check(partial.view(true,false,"").percent()==50,"Measured percentage");
  ProgressInfo sent=data(100,100,"","");check(sent.view(true,false,"").step()==2&&!sent.view(true,false,"").complete(),"File delivery does not prove installation");
  ProgressInfo confirmed=data(100,100,"target","");check(confirmed.view(true,false,"").step()==3&&!confirmed.view(true,false,"").complete(),"Version does not prove ADB");
  ProgressInfo rejected=data(100,100,"","[SSL: TLSV1_ALERT_UNKNOWN_CA]");check(rejected.view(true,false,"").warning()&&rejected.view(true,false,"").title().contains("прошивка не подошла"),"Explain certificate rejection after transfer as possible incompatible firmware");
  check(data(50,100,"","[SSL: TLSV1_ALERT_UNKNOWN_CA]").view(true,false,"").title().equals("Колонка отклонила сертификат"),"Do not blame firmware before full transfer");
  ProgressInfo timeout=new ProgressInfo("tls_error","yandexmicro","factory","target","","handshake timed out","","",0,0,100,100,1);
  check(!timeout.view(true,false,"").title().contains("прошивка"),"TLS timeout is not certificate rejection");
  check(rejected.view(true,true,"").complete(),"Working ADB overrides earlier certificate error");
  check(sent.view(true,false,"timeout").title().contains("связи"),"Disconnected backend cannot look complete");
  check(ProgressInfo.EMPTY.view(true,true,"").complete(),"Verified local ADB marks completion");
  check(!new ProgressInfo("device","yandexmicro","factory","target","target","","","",0,0,0,0,1).versionConfirmed(),"Earlier confirmation cannot hide a later factory version");
  System.out.println("PASS: 11 progress evidence checks");
 }
}
