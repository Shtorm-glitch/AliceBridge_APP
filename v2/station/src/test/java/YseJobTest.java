import dev.alicebridge.station.YseJob;
public class YseJobTest {public static void main(String[] args){
 if(!YseJob.parse("RUNNING\r\nDownloading\r\n").running())throw new AssertionError();
 if(YseJob.parse("0\r\nDone\r\n").code()!=0)throw new AssertionError();
 if(YseJob.parse("255\nError").code()!=255)throw new AssertionError();
 for(String invalid:new String[]{"","Done","0\\n","RUNNINGbad","999"})try{YseJob.parse(invalid);throw new AssertionError(invalid);}catch(IllegalArgumentException expected){}
 System.out.println("PASS: YSE job polling with CRLF and explicit completion codes");
}}
