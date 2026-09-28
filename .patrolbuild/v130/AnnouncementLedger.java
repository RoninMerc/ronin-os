package au.com.roningroup.patrollink;

import java.util.*;

/**
 * Tracks which live Issue Monitor rows have already been considered for speech.
 *
 * Eligibility is based on the previous successful live feed read, not on an
 * arbitrary "younger than five minutes" rule. That means a temporary
 * Silvertracker/network delay cannot silently age an otherwise new alert out.
 */
public final class AnnouncementLedger {
    private static final long LATE_ARRIVAL_LOOKBACK_MS = 5L * 60L * 1000L;
    private final LinkedHashMap<String,String> seen = new LinkedHashMap<>();

    public List<Observation> collect(List<Observation> rows, boolean hadBaseline,
                                     long previousReadAt, long now) {
        if (!hadBaseline) seen.clear();
        ArrayList<Observation> out = new ArrayList<>();
        long earliestEligible = previousReadAt > 0
                ? previousReadAt - LATE_ARRIVAL_LOOKBACK_MS : Long.MAX_VALUE;

        for (Observation o : rows) {
            if (o == null || o.id == null || o.id.trim().isEmpty()) continue;
            String fingerprint = o.guard + "\n" + o.issue + "\n" + o.property;
            String old = seen.put(o.id, fingerprint);
            boolean changed = old == null || !old.equals(fingerprint);
            boolean saneTime = o.recordedAt > 0 && o.recordedAt <= now + 90_000L;
            boolean arrivedSinceLastGoodRead = previousReadAt > 0
                    && o.recordedAt >= earliestEligible;

            if (hadBaseline && changed && saneTime && arrivedSinceLastGoodRead) out.add(o);
        }

        trim();
        out.sort(Comparator.comparingLong((Observation o) -> o.recordedAt)
                .thenComparing(o -> o.id));
        return out;
    }

    /**
     * Compatibility overload retained for the existing formatter/ledger tests.
     * Production live monitoring uses the four-argument previous-read form above.
     */
    public List<Observation> collect(List<Observation> rows, boolean hadBaseline, long now) {
        if (!hadBaseline) seen.clear();
        ArrayList<Observation> out = new ArrayList<>();
        for (Observation o : rows) {
            if (o == null || o.id == null || o.id.trim().isEmpty()) continue;
            String fingerprint = o.guard + "\n" + o.issue + "\n" + o.property;
            String old = seen.put(o.id, fingerprint);
            boolean changed = old == null || !old.equals(fingerprint);
            boolean recent = o.recordedAt > 0 && o.recordedAt <= now + 90_000L
                    && now - o.recordedAt <= 300_000L;
            if (hadBaseline && changed && recent) out.add(o);
        }
        trim();
        out.sort(Comparator.comparingLong((Observation o) -> o.recordedAt)
                .thenComparing(o -> o.id));
        return out;
    }

    private void trim() {
        while (seen.size() > 512) seen.remove(seen.keySet().iterator().next());
    }

    public void clear() { seen.clear(); }
}
