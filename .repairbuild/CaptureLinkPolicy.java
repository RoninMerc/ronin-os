package au.com.roningroup.patrollink;
import java.net.*;import java.util.*;
/** Read-only navigation policy; never accepts editor/delete commands or credential-bearing links. */
final class CaptureLinkPolicy {
 static final String ROUTE="(?i)/AdminCustomer/(?:(?:Issues|Reports)/)?(?:view(?:issue|action|report)?(?:details?)?|(?:issue|action|report)?details?|(?:issue|action)?reports?|(?:issue|action|report)view|(?:issue|report)?viewer)\\.aspx";
 static boolean allowed(String url){
  try{
   URI u=new URI(url);if(!"https".equalsIgnoreCase(u.getScheme())||!"trlsecurity.silvertracker.net".equalsIgnoreCase(u.getHost())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||!u.getPath().matches(ROUTE))return false;
   boolean stable=false;String q=u.getRawQuery();if(q==null)return false;
   for(String part:q.split("&")){String[] pair=part.split("=",2);String k=URLDecoder.decode(pair[0],"UTF-8"),v=pair.length>1?URLDecoder.decode(pair[1],"UTF-8"):"";
    if(k.matches("(?i)(?:password|passwd|pwd|username|login|token|session|sessionid|api[-_]?key)"))return false;
    if(k.matches("(?i)(?:action|mode|cmd|operation)")&&!v.matches("(?i)(?:view|detail|details|read|report|print|show)"))return false;
    if(k.matches("(?i)(?:issueid|actionid|reportid|id)")&&stableId(v))stable=true;
   }
   return stable;
  }catch(Exception e){return false;}
 }
 static boolean stableId(String value){return value!=null&&value.matches("(?i)(?:[0-9]{1,20}|[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");}
 private CaptureLinkPolicy(){}
}
