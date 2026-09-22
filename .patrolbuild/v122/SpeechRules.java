package au.com.roningroup.patrollink;

import java.util.*;
import java.util.regex.*;

/** Speech-only transforms. Never modifies Observation or Silvertracker records. */
public final class SpeechRules {
    private SpeechRules() {}
    public static String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    public static String key(String value) { return clean(value).toLowerCase(Locale.ROOT); }
    public static String guardKey(String value) { return clean(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    public static int speedPercent(int value) { return Math.max(75, Math.min(150, value)); }
    public static String exact(String raw, Map<String,String> rules) {
        String original = clean(raw), replacement = rules.get(key(original));
        return clean(replacement).isEmpty() ? original : clean(replacement);
    }
    public static String guard(String username, Map<String,String> nicknames) {
        String nickname = clean(nicknames.get(guardKey(username)));
        if (!nickname.isEmpty()) return nickname;
        String fallback = AnnouncementText.spokenGuard(username);
        return fallback.isEmpty() ? "Guard not supplied" : fallback;
    }
    /** One literal, whole-word/phrase pass. Longer matches win; replacements never cascade. */
    public static String phrases(String raw, Map<String,String> rules) {
        String input = clean(raw);
        ArrayList<String> keys = new ArrayList<>();
        for (Map.Entry<String,String> e : rules.entrySet()) {
            if (!clean(e.getKey()).isEmpty() && !clean(e.getValue()).isEmpty()) keys.add(key(e.getKey()));
        }
        if (keys.isEmpty()) return input;
        keys.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])(?:");
        for (int i=0; i<keys.size(); i++) {
            if(i>0)regex.append('|');
            String[] words=keys.get(i).split(" ");
            for(int j=0;j<words.length;j++){if(j>0)regex.append("\\s+");regex.append(Pattern.quote(words[j]));}
        }
        regex.append(")(?![\\p{L}\\p{N}])");
        Matcher m=Pattern.compile(regex.toString(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(input);
        StringBuffer out=new StringBuffer();
        while(m.find()) {
            String replacement=rules.get(key(m.group()));
            m.appendReplacement(out,Matcher.quoteReplacement(clean(replacement)));
        }
        m.appendTail(out);return out.toString();
    }
    public static String format(Observation o, String prefix, Map<String,String> nicknames,
                                Map<String,String> alerts, Map<String,String> places, Map<String,String> pronunciations) {
        if(o==null)throw new IllegalArgumentException("An activity is required.");
        String issue=exact(o.issue,alerts);
        issue=phrases(phrases(issue,places),pronunciations);
        String location=phrases(exact(o.property,places),pronunciations);
        StringBuilder b=new StringBuilder();
        append(b,clean(prefix));append(b,guard(o.guard,nicknames));
        append(b,issue.isEmpty()?"Activity description not supplied":issue);
        if(location.isEmpty())append(b,"Location not supplied");
        else if(!AnnouncementText.containsLocation(issue,location))append(b,location);
        return b.toString();
    }
    private static void append(StringBuilder out,String part) {
        if(part.isEmpty())return;
        if(out.length()>0)out.append(' ');
        out.append(part);
        if(!part.matches("(?s).*[.!?]$"))out.append('.');
    }
}
