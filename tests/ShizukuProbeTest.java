package dev.alice.bridge;
public class ShizukuProbeTest {
 public static void main(String[] args)throws Exception{
  ShizukuProbe.require("[78.24.223.83]","[78.24.223.83]","HTTP/1.1 204 No Content","78.24.223.83");
  String[][] bad={{"[213.180.193.230]","[78.24.223.83]","HTTP/1.1 204 No Content"},{"[78.24.223.83]","[]","HTTP/1.1 204 No Content"},{"[78.24.223.83]","[78.24.223.83]","HTTP/1.1 200 OK"}};
  for(String[] x:bad){try{ShizukuProbe.require(x[0],x[1],x[2],"78.24.223.83");throw new AssertionError("Bad path accepted");}catch(java.io.IOException expected){}}
  System.out.println("PASS: Shizuku readiness requires UDP + TCP target DNS and actual HTTP 204");
 }
}
