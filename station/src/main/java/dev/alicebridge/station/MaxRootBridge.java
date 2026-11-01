package dev.alicebridge.station;
import dev.alicebridge.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** One serialized worker connection. Never starts a second exploit for an existing session. */
public final class MaxRootBridge implements AutoCloseable {
 private final Process process;
 private final BlockingQueue<String> lines=new LinkedBlockingQueue<>(4096);
 private final BufferedWriter input;
 private final Consumer<String> log;
 final String host,boot;
 private volatile boolean closed;
 MaxRootBridge(Process process,String host,String boot,Consumer<String> log)throws IOException {
  this.process=process;this.host=host;this.boot=boot;this.log=log;
  input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
  Thread reader=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){if(line.length()>16384)line=line.substring(0,16384);if(!lines.offer(line)){lines.poll();lines.offer(line);}}}catch(IOException e){lines.offer("AB_BRIDGE_IO: "+e);}finally{closed=true;}},"max-root-bridge");reader.setDaemon(true);reader.start();
 }
 boolean alive(){return !closed&&process.isAlive();}
 synchronized String awaitReady()throws IOException,InterruptedException {return await("bridge: worker READY",360);}
 private String await(String expected,int seconds)throws IOException,InterruptedException {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);StringBuilder result=new StringBuilder();
  while(System.nanoTime()<deadline){String line=lines.poll(250,TimeUnit.MILLISECONDS);if(line!=null){if(result.length()<32768)result.append(line).append('\n');log.accept(line);if(line.contains(expected))return result.toString();if(line.contains("reader bring-up failed")||line.contains("worker never became ready")||line.contains("worker response timeout")||line.contains("IDLE-TIMEOUT")||line.startsWith("AB_BRIDGE_IO:"))throw new IOException(result.toString());}else if(!alive()&&lines.isEmpty())throw new IOException("Root bridge Макс завершился: "+result);}
  throw new IOException("Root bridge Макс: нет ответа «"+expected+"» за "+seconds+" с. "+result);
 }
 synchronized String builtin(String command,String expected)throws IOException,InterruptedException {send(command);return await(expected,30);}
 synchronized void execute(String command)throws IOException,InterruptedException {
  String marker="AB_MAX_"+java.util.UUID.randomUUID().toString().replace("-","");send(command+"; echo "+marker);await(marker,90);
 }
 private void send(String command)throws IOException {
  if(!alive())throw new IOException("Root bridge Макс закрыт");
  if(command.indexOf('\n')>=0||command.indexOf('\r')>=0||command.getBytes(StandardCharsets.UTF_8).length>1800)throw new IOException("Команда root bridge слишком длинная");
  input.write(command);input.newLine();input.flush();
 }
 @Override public void close(){if(!closed){try{input.write("exit\n");input.flush();}catch(IOException ignored){}closed=true;}try{input.close();}catch(IOException ignored){}process.destroy();}
}
