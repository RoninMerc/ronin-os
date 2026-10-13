package au.com.roningroup.patrollink;

/** Immutable Issue Monitor observation. It is activity history, not a live GPS fix. */
public final class Observation {
    public final String id, guard, property, issue, recordedText;
    public final long recordedAt;
    public Observation(String id, String guard, String property, String issue, String recordedText, long recordedAt) {
        this.id = clean(id); this.guard = clean(guard); this.property = clean(property);
        this.issue = clean(issue); this.recordedText = clean(recordedText); this.recordedAt = recordedAt;
    }
    private static String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    public String activity() {
        if (!issue.isEmpty()) return issue;
        if (!property.isEmpty()) return property;
        return "Issue " + id;
    }
    public String locationLine() {
        if (property.isEmpty()) return "Silvertracker Issue Monitor";
        return property;
    }
    /** Kept for source compatibility with older code. */
    public String checkpoint() { return activity(); }
}
