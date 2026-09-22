package au.com.roningroup.patrollink;

import java.util.Locale;

/** Formats recorded facts, never infers a guard's live position or a warning level. */
public final class VoiceReadout {
    private VoiceReadout() {}
    static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    public static String of(Observation o){
        if(o==null)throw new IllegalArgumentException("An observation is required");
        return format(o.guard,o.issue,o.property);
    }
    public static String spokenGuard(String guard){
        String[] parts=clean(guard).split("[._]+");StringBuilder b=new StringBuilder();
        for(String part:parts){
            if(part.isEmpty())continue;
            part=part.replaceAll("(?<=[A-Za-z])(?=[0-9])"," ");
            String[] words=part.split(" ");StringBuilder p=new StringBuilder();
            for(String word:words){
                if(word.length()>1&&word.matches("[A-Z]+"))word=word.substring(0,1)+word.substring(1).toLowerCase(Locale.ROOT);
                if(p.length()>0)p.append(' ');p.append(word);
            }
            if(b.length()>0)b.append(" dot ");b.append(p);
        }
        return b.toString();
    }
    public static String format(String guard,String issue,String property){
        String g=spokenGuard(guard),a=clean(issue),p=clean(property);
        StringBuilder b=new StringBuilder(g.isEmpty()?"Guard not supplied":"Guard "+g);
        if(a.isEmpty())b.append(". Activity details not supplied");
        else b.append(" recorded ").append(a);
        if(p.isEmpty()){
            end(b);b.append(" Location not supplied");
        }else if(!containsWords(a,p)){
            // Preserve the full matter, but avoid breaking the final place into an isolated word.
            if(b.length()>0&&b.charAt(b.length()-1)=='.')b.deleteCharAt(b.length()-1);
            b.append(" at ").append(p);
        }
        end(b);return b.toString();
    }
    static boolean containsWords(String text,String words){
        String a=clean(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();
        String b=clean(words).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();
        return !b.isEmpty()&&(" "+a+" ").contains(" "+b+" ");
    }
    private static void end(StringBuilder b){if(b.length()>0&&".!?".indexOf(b.charAt(b.length()-1))<0)b.append('.');}
}
