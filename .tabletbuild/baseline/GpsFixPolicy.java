package au.com.roningroup.patrollink;
/** Platform-independent validation for real, recent position samples. */
public final class GpsFixPolicy {
    private GpsFixPolicy(){}
    public static boolean valid(double latitude,double longitude,float accuracy,long fixElapsed,long nowElapsed){
        return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Float.isFinite(accuracy)
            &&latitude>=-90&&latitude<=90&&longitude>=-180&&longitude<=180&&accuracy>=0
            &&fixElapsed>0&&nowElapsed>0&&fixElapsed<=nowElapsed+2000&&nowElapsed-fixElapsed<=120000;
    }
}
