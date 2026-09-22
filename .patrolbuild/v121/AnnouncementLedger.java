package au.com.roningroup.patrollink;

import java.util.*;

/** Speech ledger independent of the small visible-history cache. */
public final class AnnouncementLedger {
    private final LinkedHashMap<String,String> seen = new LinkedHashMap<>();
    public List<Observation> collect(List<Observation> rows, boolean hadBaseline, long now) {
        if (!hadBaseline) seen.clear();
        ArrayList<Observation> out = new ArrayList<>();
        for (Observation o : rows) {
            String fingerprint = o.guard + "\n" + o.issue + "\n" + o.property;
            String old = seen.put(o.id, fingerprint);
            boolean changed = old == null || !old.equals(fingerprint);
            boolean recent = o.recordedAt > 0 && o.recordedAt <= now + 90000 && now - o.recordedAt <= 300000;
            if (hadBaseline && changed && recent) out.add(o);
        }
        while (seen.size() > 512) seen.remove(seen.keySet().iterator().next());
        out.sort(Comparator.comparingLong((Observation o) -> o.recordedAt).thenComparing(o -> o.id));
        return out;
    }
    public void clear() { seen.clear(); }
}
