package au.com.roningroup.patrollink;
import java.time.*;import java.time.format.*;import java.util.*;
final class CaptureTimestamp {
 static String parse(String monitor,String detail,ZoneId zone){
  for(String raw:new String[]{monitor,detail}){
   if(raw==null||!raw.matches("(?s).*\\b20\\d{2}\\b.*"))continue;
   String text=raw.trim().replaceAll("\\s+"," ");
   try{return Instant.parse(text).toString();}catch(Exception ignored){}
   try{return OffsetDateTime.parse(text).toInstant().toString();}catch(Exception ignored){}
   if(zone==null)continue;
   for(String pattern:new String[]{"d/M/uuuu H:mm[:ss]","d/M/uuuu h:mm[:ss] a","EEE d/M/uuuu H:mm[:ss]","EEE d/M/uuuu h:mm[:ss] a","uuuu-MM-dd H:mm[:ss]","d MMM uuuu H:mm[:ss]"}){
    try{DateTimeFormatter parser=new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);return LocalDateTime.parse(text,parser).atZone(zone).toInstant().toString();}catch(Exception ignored){}
   }
  }
  return null;
 }
 private CaptureTimestamp(){}
}
