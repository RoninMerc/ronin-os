package au.com.roningroup.patrollink;

import java.util.*;
import java.util.regex.Pattern;

/** Pure formatting; no generative summarisation and no prerecorded status phrases. */
public final class AnnouncementText {
    private AnnouncementText() {}
    public static String format(Observation o) {
        if (o == null) throw new IllegalArgumentException("An activity is required");
        String guard = spokenGuard(o.guard);
        String issue = clean(o.issue);
        String location = clean(o.property);
        StringBuilder b = new StringBuilder();
        append(b, guard.isEmpty() ? "Guard not supplied" : guard);
        append(b, issue.isEmpty() ? "Activity description not supplied" : issue);
        if (location.isEmpty()) append(b, "Location not supplied");
        else if (!containsLocation(issue, location)) append(b, location);
        return b.toString();
    }
    private static void append(StringBuilder b, String s) {
        if (b.length() > 0) b.append(' ');
        b.append(s);
        if (!s.matches(".*[.!?]$")) b.append('.');
    }
    static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    static boolean containsLocation(String activity, String location) {
        String a = clean(activity).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        String l = clean(location).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return !l.isEmpty() && Pattern.compile("(?:^| )" + Pattern.quote(l) + "(?: |$)").matcher(a).find();
    }
    public static String spokenGuard(String id) {
        String s = clean(id).replace('.', ' ').replace('_', ' ').replaceAll("(?<=[A-Za-z])(?=[0-9])", " ");
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (p.length() > 1 && p.matches("[A-Z]+")) b.append(p.charAt(0)).append(p.substring(1).toLowerCase(Locale.ROOT));
            else b.append(p);
        }
        return b.toString();
    }
}
