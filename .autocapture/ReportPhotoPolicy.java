package au.com.roningroup.patrollink;
import java.net.*;
final class ReportPhotoPolicy {
 static boolean allowed(String value){
  try{
   URI u=new URI(value);
   if(!"https".equalsIgnoreCase(u.getScheme())||!"trlsecurity.silvertracker.net".equalsIgnoreCase(u.getHost())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||u.getPath().matches("(?i).*(?:edit|delete|remove|save|logout|login).*"))return false;
   if(!(u.getPath().matches("(?i).+\\.(?:jpg|jpeg|png|webp|ashx)")||u.getPath().matches("(?i).*(?:photo|image|attachment|download|file).*\\.aspx")))return false;
   String q=u.getRawQuery();if(q!=null)for(String p:q.split("&")){String[] pair=p.split("=",2);String k=URLDecoder.decode(pair[0],"UTF-8"),v=pair.length>1?URLDecoder.decode(pair[1],"UTF-8"):"";if(k.matches("(?i)action|mode|cmd|operation")&&!v.matches("(?i)view|read|photo|image|download|show"))return false;}
   return true;
  }catch(Exception e){return false;}
 }
 private ReportPhotoPolicy(){}
}
