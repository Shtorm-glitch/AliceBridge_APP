package dev.alice.bridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class StationRootProcessTest {
    public static void main(String[] args)throws Exception{
        if(args.length>0){
            if(args.length>2&&args[2].equals("terminal")){
                Process root=new StationRootProcess(Integer.parseInt(args[0]),"/system/bin/sh '"+args[1]+"'",true);
                try{
                    root.getOutputStream().write("id; cat /proc/self/status | grep Cap; stty size; echo AB_TERMINAL_COMPLETE\r".getBytes(StandardCharsets.UTF_8));root.getOutputStream().flush();
                    StringBuilder out=new StringBuilder();InputStream in=root.getInputStream();long deadline=System.currentTimeMillis()+10000;
                    while(System.currentTimeMillis()<deadline){int b=in.read();if(b<0)break;out.append((char)b);String s=out.toString();if(s.contains("CapEff: 0000001fffffffff")&&s.contains("24 80")&&s.endsWith("AB_TERMINAL_COMPLETE\r\n"))break;}
                    System.out.println(out);if(!out.toString().contains("CapEff: 0000001fffffffff")||!out.toString().contains("24 80"))throw new AssertionError(out);
                }finally{root.destroyForcibly();}
                return;
            }
            String command=args.length>1?"/system/bin/sh '"+args[1]+"'":"id; cat /proc/self/status | grep Cap; test -t 0 && echo ROOT_PTY_OK; printf '\\nAB_ROOT_LIVE=0\\n'";
            Process root=new StationRootProcess(Integer.parseInt(args[0]),command,false);
            try{String out=new String(root.getInputStream().readAllBytes(),StandardCharsets.UTF_8);System.out.println(out);if(!out.contains("uid=0(root)")||!out.contains("CapEff: 0000001fffffffff"))throw new AssertionError(out);}finally{root.destroyForcibly();}
            return;
        }
        try(ServerSocket server=new ServerSocket(0)){
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<?> peer=executor.submit(()->{
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(3000);OutputStream out=socket.getOutputStream();InputStream in=socket.getInputStream();
                        out.write(new byte[]{(byte)255,(byte)253,31});out.flush();
                        if(in.read()!=255||in.read()!=252||in.read()!=31)throw new AssertionError("NAWS negotiation");
                        out.write("root@station:/ # ".getBytes(StandardCharsets.UTF_8));out.flush();
                        ByteArrayOutputStream command=new ByteArrayOutputStream();int value;
                        while((value=in.read())!=-1&&value!=10)command.write(value);
                        if(!command.toString(StandardCharsets.UTF_8).contains("stty -echo"))throw new AssertionError(command.toString());
                        out.write("root@station:/ # ".getBytes(StandardCharsets.UTF_8));out.flush();command.reset();
                        while((value=in.read())!=-1&&value!=10)command.write(value);
                        if(!command.toString(StandardCharsets.UTF_8).contains("test-command; exit"))throw new AssertionError(command.toString());
                        out.write("\r\nAB_TEST=7\r\n".getBytes(StandardCharsets.UTF_8));out.flush();
                    }catch(IOException error){throw new UncheckedIOException(error);}
                });
                Process root=new StationRootProcess(server.getLocalPort(),"test-command",false);
                String out=new String(root.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if(RemoteShell.parse(out,"AB_TEST=").code()!=7)throw new AssertionError(out);
                if(root.isAlive())throw new AssertionError("EOF must close session");
                peer.get(5,TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
        }
        System.out.println("PASS: Station 1 Telnet negotiation, completion code, EOF cleanup");
    }
}
