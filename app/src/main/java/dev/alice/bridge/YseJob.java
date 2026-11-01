package dev.alice.bridge;
public final class YseJob {
    public record State(boolean running,int code,String log){}
    public static State parse(String value){
        String[] lines=value.trim().split("\\R",2);String status=lines[0].trim(),log=lines.length>1?lines[1]:"";
        if(status.equals("RUNNING"))return new State(true,-1,log);
        if(!status.matches("[0-9]{1,3}"))throw new IllegalArgumentException("Неполный статус задачи YSE");
        int code=Integer.parseInt(status);if(code>255)throw new IllegalArgumentException("Некорректный код YSE");
        return new State(false,code,log);
    }
}
