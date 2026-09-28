package au.com.roningroup.patrollink;

import java.util.*;

/** Per-visible-page accounting. Re-reading one page preserves its original added result. */
public final class HistoryPageAudit {
    private String snapshot="";
    private final Set<String> addedHere=new HashSet<>();
    private final LinkedHashMap<String,int[]> summary=new LinkedHashMap<>();
    private final List<String> lines=new ArrayList<>();
    private long readAt;
    private int totalRows,unread,other;
    private String range="";

    public synchronized void accept(String fingerprint,List<Observation> rows,List<String> guards,
                                    CheckpointTracker tracker,int visibleRows,int unreadRows,long now){
        String identity=tracker.shiftStart()+"|"+guards+"|"+fingerprint;
        if(!identity.equals(snapshot)){snapshot=identity;addedHere.clear();}
        summary.clear();lines.clear();other=0;totalRows=visibleRows;unread=unreadRows;readAt=now;
        Set<String> allowed=new HashSet<>();
        for(String g:guards){allowed.add(FeedReducer.key(g));summary.put(FeedReducer.key(g),new int[4]);}
        List<Observation> selected=new ArrayList<>();
        long earliest=Long.MAX_VALUE,latest=0;
        Set<String> pageEvents=new HashSet<>();
        for(Observation row:rows){
            if(row.recordedAt>0){earliest=Math.min(earliest,row.recordedAt);latest=Math.max(latest,row.recordedAt);}
            String guard=FeedReducer.key(row.guard);
            if(!allowed.contains(guard)){other++;continue;}
            selected.add(row);
            String reason=tracker.exclusion(row);
            int[] s=summary.get(guard);String outcome;
            if(!reason.isEmpty()){
                boolean ordinary=reason.equals("Not a checkpoint/patrol scan")||reason.equals("Before shift start");
                s[ordinary?2:3]++;outcome=reason;
            }else{
                String event=CheckpointTracker.eventIdentity(row);
                if(!tracker.containsHit(row))addedHere.add(event);
                if(addedHere.contains(event)){if(pageEvents.add(event))s[0]++;outcome="ADDED on this page";}
                else{if(pageEvents.add(event))s[1]++;outcome="ALREADY COUNTED (same guard / area / timestamp)";}
            }
            lines.add(row.guard+" | "+row.recordedText+"\n"+row.property+" | "+row.issue+"\n"+outcome+" | Issue "+row.id);
        }
        tracker.observe(selected);
        range=latest==0?"Timestamp range unavailable":Long.toString(earliest)+"|"+latest;
    }
    public synchronized long readAt(){return readAt;}
    public synchronized String range(){return range;}
    public synchronized String counts(String guard){
        int[] s=summary.get(FeedReducer.key(guard));
        if(s==null)return "page not read";
        return s[0]+" added / "+s[1]+" counted / "+s[2]+" non-scan or old"+(s[3]>0?" / "+s[3]+" NEED CHECK":"");
    }
    public synchronized int added(String guard){int[] s=summary.get(FeedReducer.key(guard));return s==null?0:s[0];}
    public synchronized boolean needsCheck(){if(unread>0)return true;for(int[] s:summary.values())if(s[3]>0)return true;return false;}
    public synchronized String details(){
        if(readAt==0)return "No stable page has been read yet. Wait for Scan complete.";
        StringBuilder s=new StringBuilder("Visible records read: ").append(totalRows)
                .append("\nUnparsed records: ").append(unread).append("\nOther guards: ").append(other)
                .append("\n\nADDED stays visible when the same page is scanned again. Counts are for unique guard + area + recorded date/time, not Issue ID.\n");
        for(String line:lines)s.append("\n").append(line).append("\n");
        if(lines.isEmpty())s.append("\nNo selected-guard rows found on this page.");
        return s.toString();
    }
}
