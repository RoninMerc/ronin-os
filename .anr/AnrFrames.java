package au.com.roningroup.patrollink;
import java.io.*;import java.util.regex.*;
/** Extracts method identifiers from main-thread ANR frames; never retains raw trace lines. */
final class AnrFrames{
 static String read(InputStream in)throws IOException{
  if(in==null)return "No ANR trace available.";
  BufferedReader reader=new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));
  StringBuilder out=new StringBuilder();boolean main=false;int chars=0,frames=0;String line;
  Pattern method=Pattern.compile("^\\s+at\\s+([A-Za-z_$][A-Za-z0-9_.$/<>-]*)\\s*\\([^)]*\\).*");
  Pattern number=Pattern.compile(":(\\d+)\\)");
  while((line=reader.readLine())!=null&&chars<262144&&frames<30){
   chars+=line.length();if(line.startsWith("\"main\"")){main=true;out.append("Blocked main-thread methods:\n");continue;}
   if(main&&line.startsWith("\""))break;
   if(!main)continue;java.util.regex.Matcher m=method.matcher(line);if(!m.matches())continue;
   out.append("  ").append(m.group(1));java.util.regex.Matcher n=number.matcher(line);if(n.find())out.append(":").append(n.group(1));out.append("\n");frames++;
  }
  return frames==0?"ANR trace had no readable main-thread Java frames.":out.toString();
 }
 private AnrFrames(){}
}
