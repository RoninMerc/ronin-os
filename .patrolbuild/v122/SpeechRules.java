package au.com.roningroup.patrollink;

import java.util.*;
import java.util.regex.*;

/** Speech-only literal substitutions. Source observations are never modified. */
public final class SpeechRules {
    public static final class Rule {
        public final String id, original, spoken;
        public final boolean phrase, enabled;
        final Pattern pattern;
        public Rule(String id,String original,String spoken,boolean phrase,boolean enabled) {
            this.id=id;this.original=clean(original);this.spoken=clean(spoken);this.phrase=phrase;this.enabled=enabled;
            StringBuilder p=new StringBuilder();
            for(String word:this.original.split(" ")) { if(p.length()>0)p.append("[\\s\\p{Z}]+");p.append(Pattern.quote(word)); }
            if(!this.original.isEmpty()) {
                if(Character.isLetterOrDigit(this.original.codePointAt(0)))p.insert(0,"(?<![\\p{L}\\p{N}_])");
                if(Character.isLetterOrDigit(this.original.codePointBefore(this.original.length())))p.append("(?![\\p{L}\\p{N}_])");
            }
            pattern=Pattern.compile(p.toString(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        }
    }
    private static final class Span {
        final int start,end; final String value;
        Span(int a,int b,String value){start=a;end=b;this.value=value;}
    }
    private final Map<String,String> exact=new HashMap<>();
    private final List<Rule> phrases=new ArrayList<>();
    public SpeechRules(Collection<Rule> rules) {
        for(Rule r:rules)if(r.enabled&&!r.original.isEmpty()&&!r.spoken.isEmpty()) {
            if(r.phrase)phrases.add(r);else exact.put(key(r.original),r.spoken);
        }
        phrases.sort(Comparator.<Rule>comparingInt(r->r.original.length()).reversed().thenComparing(r->r.id));
    }
    public static String clean(String s){return s==null?"":s.replaceAll("[\\s\\p{Z}]+"," ").trim();}
    public static String key(String s){return clean(s).toLowerCase(Locale.ROOT);}
    public String apply(String source) {
        String text=clean(source),whole=exact.get(key(text));if(whole!=null)return whole;
        List<Span> spans=new ArrayList<>();
        for(Rule rule:phrases) {
            Matcher m=rule.pattern.matcher(text);
            while(m.find()) {
                boolean overlap=false;for(Span s:spans)if(m.start()<s.end&&m.end()>s.start){overlap=true;break;}
                if(!overlap)spans.add(new Span(m.start(),m.end(),rule.spoken));
            }
        }
        spans.sort(Comparator.comparingInt(s->s.start));
        StringBuilder out=new StringBuilder();int pos=0;
        for(Span s:spans){out.append(text,pos,s.start).append(s.value);pos=s.end;}
        out.append(text,pos,text.length());return out.toString();
    }
    public String readout(Observation source,Map<String,String> nicknames) {
        if(source==null)throw new IllegalArgumentException("Choose an activity to read.");
        String nickname=nicknames.get(FeedReducer.key(source.guard));
        String guard=clean(nickname).isEmpty()?VoiceReadout.spokenGuard(source.guard):clean(nickname);
        return VoiceReadout.formatSpokenName(guard,apply(source.issue),source.property);
    }
}
