package au.com.roningroup.patrollink;

import android.app.Application;

public final class PatrolApp extends Application {
    private PatrolEngine engine;
    public PatrolEngine engine() {
        if (engine == null) engine = new PatrolEngine(this);
        return engine;
    }
}
