package au.com.roningroup.patrollink;

import java.util.*;
import java.util.regex.*;

/** A matching enabled replacement IS the complete announcement, not one field in a template. */
public final class SpeechRules {
    public static final class Rule {
        public final String id,original,spoken;public final boolean phrase,enabled;final Pattern pattern;
        public Rule(String id,String original,String spoken,boolean phrase,boolean enabled){
            this.id=id;this.original=clean(original);this.spoken=clean(spoken);this.phrase=phrase;this.enabled=enabled;
            StringBuilder p=new StringBuilder();for(String word:this.original.split(" ")){if(p.length()>0)p.append("[\\s\\p{Z}]+");p.append(Pattern.quote(word));}
            if(!this.original.isEmpty()){if(Character.isLetterOrDigit(this.original.codePointAt(0)))p.insert(0,"(?<![\\p{L}\\p{N}_])");if(Character.isLetterOrDigit(this.original.codePointBefore(this.original.length())))p.append("(?![\\p{L}\\p{N}_])");}
            pattern=Pattern.compile(p.toString(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        }
    }
    private final Map<String,String> exact=new HashMap<>();private final List<Rule> phrases=new ArrayList<>();
    public SpeechRules(Collection<Rule> rules){
        for(Rule r:rules)if(r.enabled&&!r.original.isEmpty()&&!r.spoken.isEmpty()){if(r.phrase)phrases.add(r);else exact.put(key(r.original),r.spoken);}
        phrases.sort(Comparator.<Rule>comparingInt(r->r.original.length()).reversed().thenComparing(r->r.id));
    }
    public static String clean(String s){return s==null?"":s.replaceAll("[\\s\\p{Z}]+"," ").trim();}
    public static String key(String s){return clean(s).toLowerCase(Locale.ROOT);}
    public String replacement(String source){
        String text=clean(source),whole=exact.get(key(text));if(whole!=null)return whole;
        // The toggle widens matching only. It NEVER keeps the unedited remainder in speech.
        for(Rule r:phrases)if(r.pattern.matcher(text).find())return r.spoken;
        return null;
    }
    public String apply(String source){String result=replacement(source);return result==null?clean(source):result;}
    public String readout(Observation source,Map<String,String> nicknames){
        if(source==null)throw new IllegalArgumentException("Choose an activity to read.");
        String finalMessage=replacement(source.issue);
        if(finalMessage!=null)return finalMessage;
        String nickname=nicknames.get(FeedReducer.key(source.guard));String guard=clean(nickname).isEmpty()?VoiceReadout.spokenGuard(source.guard):clean(nickname);
        return VoiceReadout.formatSpokenName(guard,source.issue,source.property);
    }
}
