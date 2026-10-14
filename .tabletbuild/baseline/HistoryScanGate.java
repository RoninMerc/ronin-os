package au.com.roningroup.patrollink;

/** Require two identical non-busy DOM reads; button taps never restart this clock. */
public final class HistoryScanGate {
    private String candidate="";
    private long since;
    public void reset(){candidate="";since=0;}
    public boolean ready(String snapshot,boolean busy,long now){
        if(busy||snapshot==null){reset();return false;}
        if(!snapshot.equals(candidate)){candidate=snapshot;since=now;return false;}
        return now-since>=500L;
    }
}
