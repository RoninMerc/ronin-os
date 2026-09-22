package au.com.roningroup.patrollink;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formats recorded facts, never infers a guard's live location or warning level. */
public final class VoiceReadout {
    private VoiceReadout() {}
    static String clean(String s) { return s==null?"":s.replaceAll("\\s+"," ").trim(); }
    public static String of(Observation o) {
        if(o==null) throw new IllegalArgumentException("An observation is required");
        return format(o.guard,o.issue,o.property);
    }
    public static String format(String guard,String issue,String property) {
        String g=clean(guard),a=clean(issue),p=clean(property);
        // Separate Created By initials for intelligible speech; leave the actual row unchanged.
        g=g.replaceAll("[._]+"," ").replaceAll("(?<=[A-Za-z])(?=[0-9])"," ");
        StringBuilder b=new StringBuilder();
        append(b,g.isEmpty()?"Guard not supplied":g);
        append(b,a.isEmpty()?"Activity details not supplied":a);
        if(p.isEmpty()) append(b,"Location not supplied");
        else if(!containsWords(a,p)) append(b,p);
        return b.toString();
    }
    static boolean containsWords(String text,String words) {
        String a=clean(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();
        String b=clean(words).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();
        return !b.isEmpty() && (" "+a+" ").contains(" "+b+" ");
    }
    private static void append(StringBuilder b,String text) {
        if(b.length()>0)b.append(' ');
        b.append(text);
        if(!text.matches(".*[.!?]$"))b.append('.');
    }
}
