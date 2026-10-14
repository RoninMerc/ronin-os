package au.com.roningroup.patrollink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MainActivity extends Activity implements PatrolEngine.Listener {
    private static final int REQ_VOICE_AUDIO = 4101;
    private static final int REQ_EXACT_PACK = 4102;
    private static final int REQ_VOICE_ADDON = 4103;
    private static final int REQ_COMPLETE_LIBRARY = 4104;
    private static final int REQ_SCRIPT_TEXT = 4105;
    private static final int BG=0xff090909, PANEL=0xff19191c, LINE=0xff35353b, WHITE=0xfff7f7f7, MUTED=0xffaaaab0, ACCENT=0xffd71920, TRL_RED=0xffd71920, AMBER=0xffe4b771, RED=0xffe68181;
    private PatrolEngine engine;
    private TabletDashboard tabletDashboard;
    private LocalGpsUi gpsUi;
    private CheckpointProximityUi checkpointUi;
    private FleetUi fleetUi;
    private AppUpdateManager updateManager;
    private TextView gpsSummary, fleetSummary;

    private LinearLayout root, dashboard;
    private TextView stateText, detailText, readText, countdownText, voiceStatusText, browserTallyText;
    private final TextView[] guardTitles = new TextView[3], activities = new TextView[3], activityMeta = new TextView[3], flags = new TextView[3];
    private final LinearLayout[] guardCards = new LinearLayout[3];
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean browserMode;
    private WebView attachedWeb;
    private AlertDialog voiceDialog;
    private final Runnable tick = new Runnable() { public void run() { refresh(); timer.postDelayed(this, 1000); }};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = ((PatrolApp)getApplication()).engine();
        TabletSettings.orient(this);
        gpsUi=new LocalGpsUi(this);
        checkpointUi=new CheckpointProximityUi(this);
        fleetUi=new FleetUi(this,()->{engine.changeFeedMode();showDashboard();});
        updateManager=new AppUpdateManager(this);
        engine.add(this); engine.appVisible=true;
        showDashboard(); ensureMonitoring();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);
        else timer.postDelayed(()->{if(!isFinishing())gpsUi.offerFirstRun();},350);
        if(getIntent().getBooleanExtra("open_patrol_gps",false))timer.post(()->gpsUi.show());
        updateManager.autoCheck();
        fleetUi.maybeSetup();
    }
    private void ensureMonitoring() {
        if (!engine.running) {
            try { startForegroundService(new Intent(this,MonitorService.class)); }
            catch(RuntimeException e) { engine.start(); }
        }
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(3),1); return t;
    }
    private void gap(LinearLayout l, int height) { View v = new View(this); l.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(12); parent.addView(card,lp);
    }
    private LinearLayout card() { LinearLayout c = vertical(); c.setPadding(dp(18),dp(16),dp(18),dp(16)); c.setBackground(background(PANEL,LINE)); return c; }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); b.setBackground(background(primary ? ACCENT : PANEL, primary ? 0 : LINE));
        b.setPadding(dp(12),dp(12),dp(12),dp(12)); b.setMinHeight(dp(52)); b.setOnClickListener(v->action.run()); return b;
    }
    private Button dangerButton(String label, Runnable action) { Button b=button(label,false,action); b.setTextColor(RED); return b; }
    private void buttonRow(LinearLayout parent, Button left, Button right) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0,dp(54),1); a.rightMargin=dp(5);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0,dp(54),1); b.leftMargin=dp(5);
        row.addView(left,a); row.addView(right,b); addCard(parent,row);
    }
    private void base() {
        if(tabletDashboard!=null){tabletDashboard.close();tabletDashboard=null;}
        MonitorFilterUi.unbind(this);
        detachWeb();
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        host.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        attachedWeb = engine.webView();
        if (attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb);
        attachedWeb.setVisibility(View.VISIBLE);
        attachedWeb.setImportantForAccessibility(browserMode ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        host.addView(attachedWeb,new FrameLayout.LayoutParams(-1,-1));
        root=vertical(); root.setBackgroundColor(BG); root.setClickable(true);
        host.addView(root,new FrameLayout.LayoutParams(-1,-1));
        setContentView(host); host.requestApplyInsets();
        attachedWeb.onResume(); attachedWeb.resumeTimers();
        // The WebView remains sized and attached underneath the opaque dashboard.
        // Never hide it with GONE or leave it detached while polling the webpage.
    }
    private void showDashboard() {
        if(TabletDashboard.enabled(this)){showTabletDashboard();return;}
        browserMode=false; engine.browserVisible=false; base();
        root.setId(153100);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        dashboard=vertical(); dashboard.setPadding(dp(22),dp(24),dp(22),dp(20)); scroll.addView(dashboard);
        LinearLayout brandRow=new LinearLayout(this);brandRow.setOrientation(LinearLayout.HORIZONTAL);brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon=new ImageView(this);brandIcon.setImageResource(R.drawable.trl_patrol_icon);brandIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        brandRow.addView(brandIcon,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout brandText=vertical();brandText.setPadding(dp(12),0,0,0);
        TextView eyebrow=text("TRL SECURITY  /  PATROL SYSTEMS",11,TRL_RED,true);eyebrow.setLetterSpacing(.12f);brandText.addView(eyebrow);
        TextView wordmark=text("TRL",36,WHITE,true);wordmark.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC));brandText.addView(wordmark);
        brandText.addView(text("THIN RED LINE SECURITY",11,WHITE,true));
        brandText.addView(text("PATROL LINK / OPERATIONS",12,TRL_RED,true));
        brandRow.addView(brandText,new LinearLayout.LayoutParams(0,-2,1));dashboard.addView(brandRow);gap(dashboard,6);
        dashboard.addView(text("LIVE SHIFT ACTIVITY",11,MUTED,true)); gap(dashboard,10);
        Button display=button("Display mode · "+TabletSettings.displayMode(this),false,this::displayMode);display.setId(153101);addCard(dashboard,display);gap(dashboard,10);

        LinearLayout status=card(); stateText=text("",14,ACCENT,true); status.addView(stateText); gap(status,8);
        detailText=text("",13,MUTED,false); status.addView(detailText); gap(status,14);
        readText=text("",12,WHITE,false); status.addView(readText); gap(status,5);
        countdownText=text("",12,MUTED,false); status.addView(countdownText); gap(status,8); voiceStatusText=text("",12,MUTED,false); status.addView(voiceStatusText); addCard(dashboard,status);

        for(int i=0;i<3;i++) {
            final int index=i;
            LinearLayout c=card(); guardCards[i]=c; c.setClickable(true); c.setFocusable(true); c.setOnClickListener(v->showCheckpointTally(index));
            guardTitles[i]=text("",12,ACCENT,true); c.addView(guardTitles[i]); gap(c,10);
            activities[i]=text("Waiting for activity",22,WHITE,true); c.addView(activities[i]); gap(c,8);
            activityMeta[i]=text("",13,MUTED,false); c.addView(activityMeta[i]); gap(c,8);
            flags[i]=text("",11,AMBER,true); c.addView(flags[i]); gap(c,8);
            c.addView(text("Tap for this guard's PBC checkpoint tally",11,MUTED,false));
            addCard(dashboard,c);
        }

        LinearLayout fleet=card();fleetSummary=text(fleetUi.summary(),13,ACCENT,true);fleet.addView(fleetSummary);gap(fleet,8);
        fleet.addView(text(fleetUi.master()?"Supervisor-only fleet view · Master never publishes patrol GPS":"Patrol GPS + preconfigured fleet relay · 15/30 second GPS",12,MUTED,false));gap(fleet,8);
        fleet.addView(button(fleetUi.master()?"Open Supervisor Fleet GPS":"Patrol duty / device role",false,fleetUi::open));addCard(dashboard,fleet);
        addCard(dashboard,button("Master SilverTrack feed · Pairing",false,()->MasterFeedUi.show(this,engine)));
        LinearLayout gps=card();gpsSummary=text(gpsUi.summary(),13,ACCENT,true);gps.addView(gpsSummary);gap(gps,8);
        gps.addView(text("Last GPS position of this phone · separate from Silvertracker activity",12,MUTED,false));gap(gps,8);
        gps.addView(button("Patrol GPS",false,()->gpsUi.show()));addCard(dashboard,gps);
        View proximity=checkpointUi.dashboardCard();addCard(dashboard,proximity);
        boolean userMode=fleetUi.configured()&&!fleetUi.master();
        if(userMode){
            buttonRow(dashboard,button("Master feed setup",false,()->MasterFeedUi.show(this,engine)),button("Diagnostics",false,this::diagnostics));
        }else{
            buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));
            addCard(dashboard,button("Speech controls",false,()->new SpeechSettingsUi(this,engine).show()));
        addCard(dashboard,button("Featherless AI · Models & voices",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show()));
            addCard(dashboard,new EntireScriptCopyUi(this,engine.voices));
            buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));
        }
        addCard(dashboard,button("Check for TRL Patrol Link update",false,()->updateManager.check(true)));
        addCard(dashboard,dangerButton("Exit / sign out",this::confirmExit));
        dashboard.addView(text("The main screen only shows each selected guard's latest Issue Monitor activity. Tap a guard card to view that guard's PBC 2 / PBC 3 checkpoint tally.",12,MUTED,false)); gap(dashboard,12);
        TextView help=text("TRL Patrol Link information",13,ACCENT,true); help.setPadding(0,dp(8),0,dp(8)); help.setOnClickListener(v->help()); dashboard.addView(help);
        refresh();
    }
    private void showTabletDashboard(){
        browserMode=false;engine.browserVisible=false;base();
        tabletDashboard=new TabletDashboard(this,engine,checkpointUi,this::tabletAction);
        root.addView(tabletDashboard.view,new LinearLayout.LayoutParams(-1,-1));
        tabletDashboard.resume();
    }
    private void displayMode(){DisplayModeUi.show(this,this::recreate);}
    private void tabletAction(String action){
        if(action.startsWith("guard:")){showCheckpointTally(Integer.parseInt(action.substring(6)));return;}
        switch(action){
            case "settings":tabletDashboard.settings();break;
            case "display":displayMode();break;
            case "gps":gpsUi.show();break;
            case "fleet":fleetUi.open();break;
            case "checkpoints":checkpointUi.showManager();break;
            case "guards":if(MasterFeedClient.master(this))settings();break;
            case "silvertrack":if(fleetUi.master())showBrowser();break;
            case "pairing":MasterFeedUi.show(this,engine);break;
            case "diagnostics":diagnostics();break;
            case "speech":if(fleetUi.master())new SpeechSettingsUi(this,engine).show();break;
            case "voices":if(fleetUi.master())voiceLibrary();break;
            case "featherless":if(fleetUi.master())new FeatherlessVoiceUi(this,engine.featherlessVoice).show();break;
            case "script":if(fleetUi.master()){LinearLayout box=vertical();box.addView(new EntireScriptCopyUi(this,engine.voices));new AlertDialog.Builder(this).setTitle("Voice script").setView(box).setPositiveButton("Close",null).show();}break;
            case "update":updateManager.check(true);break;
            case "help":help();break;
            case "exit":confirmExit();break;
        }
    }
    private void showCheckpointTally(int index) {
        String guard=engine.guards().get(index);
        String nickname=engine.voices.speechSettings.nickname(guard);
        String shownName=nickname==null||nickname.trim().isEmpty()?guard:nickname.trim()+"  ·  "+guard;

        LinearLayout list=vertical();list.setPadding(dp(16),dp(8),dp(16),dp(8));
        long started=engine.checkpoints.shiftStart();
        String since=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(started).atZone(engine.zone()));

        list.addView(text("SHIFT CHECKPOINT TALLY",11,ACCENT,true));gap(list,5);
        list.addView(text(shownName,22,WHITE,true));gap(list,5);
        list.addView(text("Unique hit = area + Silvertracker recorded time + guard. The same page can be scanned again without increasing the count. The same area/time hit by a different guard counts separately.",12,MUTED,false));gap(list,8);
        list.addView(text("Shift starts "+since+". New General Patrol/scan areas are learned automatically; they do not need to be pre-programmed.",12,MUTED,false));gap(list,16);

        final AlertDialog[] holder=new AlertDialog[1];
        addCard(list,button("Open Silvertracker · scan older pages",true,()->{
            if(holder[0]!=null)holder[0].dismiss();
            showBrowser();
        }));

        java.util.List<CheckpointTracker.AreaCount> areas=engine.checkpoints.areas(guard);
        if(areas.isEmpty()){
            LinearLayout empty=card();
            empty.addView(text("No checkpoint hits recorded for this guard yet.",15,MUTED,false));
            addCard(list,empty);
        } else {
            for(CheckpointTracker.AreaCount area:areas){
                LinearLayout c=card();
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
                TextView label=text(area.label,15,WHITE,true);
                TextView count=text(String.valueOf(area.count),20,ACCENT,true);
                count.setGravity(Gravity.END);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                row.addView(count,new LinearLayout.LayoutParams(dp(58),-2));
                c.addView(row);
                if(area.lastAt>0){
                    gap(c,5);
                    String when=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(area.lastAt).atZone(engine.zone()));
                    c.addView(text("Latest counted hit: "+when,11,MUTED,false));
                }
                addCard(list,c);
            }
        }

        LinearLayout totalCard=card();
        totalCard.addView(text("TOTAL CHECKPOINT HITS  ·  "+engine.checkpoints.total(guard),16,ACCENT,true));
        addCard(list,totalCard);

        addCard(list,dangerButton("Clear & restart current 18:00 shift",()->new AlertDialog.Builder(this)
            .setTitle("Clear shift tally?")
            .setMessage("This clears checkpoint counts for all three guards and restarts the tally from the current 18:00 shift boundary.")
            .setPositiveButton("Clear",(d,w)->{
                engine.checkpoints.reset();
                toast("Shift tally cleared. Counting again from 18:00.");
                if(holder[0]!=null)holder[0].dismiss();
                showCheckpointTally(index);
            }).setNegativeButton("Cancel",null).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        holder[0]=new AlertDialog.Builder(this).setTitle("Checkpoint tally").setView(scroll).setPositiveButton("Close",null).create();
        holder[0].show();
    }

    private void showBrowser() {
        if(MasterFeedClient.subscriber(this)){MasterFeedUi.show(this,engine);return;}
        browserMode=true; engine.browserVisible=true; browserTallyText=null; base();
        FrameLayout page=new FrameLayout(this);page.setId(155200);
        attachedWeb=engine.webView();
        if(attachedWeb.getParent() instanceof ViewGroup)((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb);
        SilverTrackBrowserUi.prepare(attachedWeb);
        page.addView(attachedWeb,new FrameLayout.LayoutParams(-1,-1));
        root.addView(page,new LinearLayout.LayoutParams(-1,-1));
        Button cog=button("\u2699",false,this::browserMenu);cog.setId(155201);cog.setTextSize(26);cog.setContentDescription("SilverTrack tools");
        FrameLayout.LayoutParams tools=new FrameLayout.LayoutParams(dp(52),dp(48),Gravity.BOTTOM|Gravity.LEFT);tools.setMargins(dp(10),dp(10),dp(10),dp(10));page.addView(cog,tools);
        MonitorFilterUi.bind(this,attachedWeb);
        if(attachedWeb.getUrl()==null||"about:blank".equals(attachedWeb.getUrl()))engine.openMonitor();else engine.scanVisiblePage();
    }
    private void browserMenu(){
        String[] choices={"Return to TRL dashboard","Reported issues · select multiple","Capture parking reports","Scan this page","Previous SilverTrack page","Next SilverTrack page","Fit page to screen",engine.rememberSilvertrackerLogin()?"Remember login · ON":"Remember login · OFF","Page scan details","Connection diagnostics"};
        new AlertDialog.Builder(this).setTitle("TRL · SilverTrack tools").setItems(choices,(dialog,which)->{
            switch(which){
                case 0:showDashboard();engine.resumeLiveFeed();break;
                case 1:MonitorFilterUi.show(this,engine.webView());break;
                case 2:VehicleCaptureUi.show(this,engine);break;
                case 3:engine.scanVisiblePage();break;
                case 4:engine.pageHistory(false);break;
                case 5:engine.pageHistory(true);break;
                case 6:SilverTrackBrowserUi.prepare(engine.webView());engine.webView().reload();break;
                case 7:toggleRememberLogin();break;
                case 8:TextView report=text(engine.historyReadStatus+"\n\n"+engine.historyAudit.details(),13,WHITE,false);report.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(report);new AlertDialog.Builder(this).setTitle("Page scan details").setView(scroll).setPositiveButton("Close",null).show();break;
                case 9:diagnostics();break;
            }
        }).setNegativeButton("Close",null).show();
    }

    private void toggleRememberLogin(){
        boolean next=!engine.rememberSilvertrackerLogin();
        engine.setRememberSilvertrackerLogin(next);
        if(next){
            new AlertDialog.Builder(this).setTitle("Remember Silvertracker login")
                .setMessage(engine.hasRememberedSilvertrackerLogin()
                    ? "Secure auto-login is enabled for this device."
                    : "Enabled. If Silvertracker is currently signed out, sign in normally once. Patrol Link will capture that submitted login and encrypt it with Android Keystore. It is never included in diagnostics.")
                .setPositiveButton("OK",null).show();
        }else toast("Remembered Silvertracker login cleared from this device.");
        showBrowser();
    }

    private void refreshBrowserTally() {
        if(browserTallyText==null)return;
        String start=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(
                Instant.ofEpochMilli(engine.checkpoints.shiftStart()).atZone(engine.zone()));
        StringBuilder totals=new StringBuilder("SHIFT FROM ").append(start);
        for(String guard:engine.guards()){
            totals.append("\n").append(guard).append(" · ").append(engine.checkpoints.total(guard))
                    .append(" total | ").append(engine.historyAudit.counts(guard));
        }
        totals.append("\n").append(engine.historyReadStatus);
        if(engine.historyAudit.readAt()>0)totals.append(" · ").append(DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(engine.historyAudit.readAt()).atZone(engine.zone())));
        browserTallyText.setText(totals.toString());
        browserTallyText.setTextColor(engine.historyAudit.needsCheck()?AMBER:ACCENT);
    }

    private void detachWeb() { if(attachedWeb != null && attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb); attachedWeb=null; }

    @Override public void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (engine.peekWebView() != null && attachedWeb != engine.peekWebView()) {
            if (browserMode) showBrowser(); else showDashboard();
            return;
        }
        refresh();
    }
    private void refresh() {
        if(tabletDashboard!=null&&!browserMode){tabletDashboard.refresh();return;}
        if(browserMode){refreshBrowserTally();return;}
        if(stateText==null)return;
        long now=System.currentTimeMillis();
        if(gpsSummary!=null)gpsSummary.setText(gpsUi.summary());
        if(checkpointUi!=null)checkpointUi.tick();
        if(voiceStatusText!=null) { voiceStatusText.setText("Voice · "+(engine.prefs.getBoolean("voice",true)?(engine.featherlessVoice.selected()?engine.featherlessVoice.status():engine.voices.status()):"Muted")); voiceStatusText.setTextColor((engine.featherlessVoice.selected()?engine.featherlessVoice.error():engine.voices.error()).isEmpty()?MUTED:AMBER); }
        stateText.setText(engine.sourceStatus()); stateText.setTextColor(engine.healthy()?ACCENT:AMBER); detailText.setText(engine.detail);
        String last=engine.lastRead==0?"No successful refresh yet":DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(engine.lastRead).atZone(engine.zone()));
        readText.setText("Last successful refresh: "+last);
        long wait=Math.max(0,(engine.nextCheckElapsed-SystemClock.elapsedRealtime()+999)/1000);
        countdownText.setText("SIGN_IN_REQUIRED".equals(engine.state)?"Automatic refresh paused until manual sign-in":engine.running?"Adaptive refresh: 10s normal · 15/30s backoff · next check in "+wait+"s":"Monitor stopped");
        List<String> guards=engine.guards();
        for(int i=0;i<3;i++) {
            String guard=guards.get(i), key=FeedReducer.key(guard); Observation o=engine.latest.get(key); guardTitles[i].setText(guard);
            guardCards[i].setEnabled(!GuardDirectory.inactive(guard));
            if(GuardDirectory.inactive(guard)){
                activities[i].setText("Unused guard slot");activityMeta[i].setText("Select a guard in Guard settings to enable this slot.");flags[i].setText("N/A");flags[i].setTextColor(MUTED);continue;
            }
            if(o==null) {
                activities[i].setText("Waiting for activity"); activityMeta[i].setText("No matching Issue Monitor entry has been read yet.");
                flags[i].setText(engine.sourceStatus()); flags[i].setTextColor(AMBER); continue;
            }
            activities[i].setText(o.activity());
            String recorded=o.recordedAt>0?DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(o.recordedAt).atZone(engine.zone())):o.recordedText+" · time unverified";
            activityMeta[i].setText(o.locationLine()+"  ·  "+recorded+"  /  "+TimeParser.age(o.recordedAt,now));
            String flag;
            if(!engine.healthy()) flag=engine.sourceStatus();
            else if(!engine.present.contains(key)) flag="NOT IN CURRENT LIST · RETAINED";
            else if(o.recordedAt==0) flag="ACTIVITY AGE UNKNOWN";
            else if(now-o.recordedAt>engine.oldMinutes()*60_000L) flag="OLDER ACTIVITY";
            else flag="LATEST RECORDED ACTIVITY";
            flags[i].setText(flag); flags[i].setTextColor(engine.healthy() && engine.present.contains(key) ? ACCENT:AMBER);
        }
    }
    private EditText input(LinearLayout form,String label,String value,int type) {
        form.addView(text(label,12,MUTED,true)); EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setTextColor(WHITE);e.setTextSize(16);e.setInputType(type);e.setPadding(dp(2),dp(10),dp(2),dp(12));
        form.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(form,12);return e;
    }
    private void settings() {
        LinearLayout form=vertical();form.setPadding(dp(22),dp(10),dp(22),dp(10));
        Spinner[] fields=new Spinner[3];List<String> options=GuardDirectory.options(engine.prefs,engine.guards());
        for(int i=0;i<3;i++){
            form.addView(text("GUARD "+(i+1)+" · SILVERTRACKER CREATED BY",12,MUTED,true));
            fields[i]=new Spinner(this);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);fields[i].setAdapter(adapter);
            int selected=options.indexOf(FeedReducer.key(engine.guards().get(i)));fields[i].setSelection(Math.max(0,selected));
            form.addView(fields[i],new LinearLayout.LayoutParams(-1,dp(52)));gap(form,8);
        }
        form.addView(text("IDs are learned from Created By on Silvertracker pages already read, including other guards. Current selections are retained. Read more pages to discover more IDs. N/A disables an unused slot; it does not erase its previous tallies.",12,MUTED,false));gap(form,10);
        EditText zone=input(form,"SITE TIME ZONE",engine.zone().getId(),InputType.TYPE_CLASS_TEXT);
        EditText old=input(form,"FLAG ACTIVITY OLDER THAN (MINUTES)",String.valueOf(engine.oldMinutes()),InputType.TYPE_CLASS_NUMBER);
        CheckBox voice=new CheckBox(this);voice.setText("Speak full guard, activity and location in the selected voice");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Guard settings").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                List<String> chosen=new ArrayList<>();for(Spinner field:fields)chosen.add(String.valueOf(field.getSelectedItem()));GuardDirectory.validate(chosen);
                String zoneText=zone.getText().toString().trim();ZoneId.of(zoneText);int minutes=Integer.parseInt(old.getText().toString());
                if(minutes<1||minutes>240)throw new IllegalArgumentException("Use an age threshold between 1 and 240 minutes.");
                SharedPreferences.Editor edit=engine.prefs.edit();for(int i=0;i<3;i++)edit.putString("guard"+i,chosen.get(i));
                edit.putString("zone",zoneText).putInt("oldMinutes",minutes).putBoolean("patrolOnly",false).putBoolean("voice",voice.isChecked()).apply();
                if(MasterFeedClient.master(this))FleetRelay.publishGuards(this,chosen);
                engine.latest.clear();engine.present.clear();engine.lastRead=engine.lastReadElapsed=0;
                engine.status("CHECKING","Guard selection updated. Refreshing Silvertracker…");engine.refreshMonitor();dialog.dismiss();refresh();
            }catch(Exception ex){toast(ex.getMessage()==null?"Check the settings.":ex.getMessage());}
        }));dialog.show();
    }

    private void voiceLibrary() {
        if(voiceDialog!=null)voiceDialog.dismiss();
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        VoiceManager.Profile active=engine.voices.activeProfile();
        java.util.List<VoiceManager.Profile> profiles=engine.voices.profiles();

        form.addView(text("ACTIVE VOICE",11,MUTED,true));gap(form,5);
        form.addView(text(active.name,26,WHITE,true));gap(form,6);
        form.addView(text(engine.voices.status(),12,engine.voices.ready()?ACCENT:AMBER,true));gap(form,8);
        form.addView(text("Output mode · "+(engine.featherlessVoice.selected()?"Featherless AI cloud TTS":"Exact WAV / Tiff local"),12,ACCENT,true));gap(form,8);
        addCard(form,button("Featherless AI · Models & voices",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show()));
        form.addView(text(engine.voices.installedPhraseCount()+" exact recordings installed",12,MUTED,false));gap(form,10);
        form.addView(text("This is now an expandable phrase library. Keep the recordings you already have and add small WAVs whenever a new street, guard, function or pronunciation is needed. Each add-on supports up to 500 phrases. There are no Part 1 / Part 2 / Part 3 limits.",13,MUTED,false));gap(form,12);
        form.addView(text("If an add-on contains a phrase that is already installed, only that phrase is replaced. Everything else stays untouched.",12,MUTED,false));gap(form,14);

        if(engine.voices.hasBundledTiff()){
            form.addView(text(engine.voices.tiffStatus(),13,engine.voices.tiffReady()?ACCENT:AMBER,true));gap(form,8);
            addCard(form,button(engine.voices.tiffReady()?"Use Tiff":"Finish Tiff setup",true,()->{
                if(engine.voices.tiffReady()){engine.voices.selectTiff();voiceLibrary();return;}
                engine.voices.prepareBundledTiff((ok,message)->{
                    if(isFinishing()||isDestroyed())return;
                    if(ok){engine.voices.selectTiff();toast(message);voiceLibrary();}
                    else new AlertDialog.Builder(this).setTitle("Tiff setup needs the matching script")
                        .setMessage(message).setPositiveButton("OK",null).show();
                });
                toast("Preparing the included Tiff recordings…");
            }));
        }

        for(VoiceManager.Profile profile:profiles){
            boolean selected=profile.id.equals(active.id);
            addCard(form,button((selected?"●  ":"○  ")+profile.name,selected,()->{
                engine.voices.setActive(profile.id);toast(profile.name+" selected.");voiceLibrary();
            }));
        }

        addCard(form,button("Add / replace exact phrases",true,this::showAddVoicePhrases));
        addCard(form,button("Test "+active.name,false,engine.voices::test));
        addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));
        addCard(form,button("Add voice profile",false,this::addVoiceProfile));

        if(profiles.size()>1){
            addCard(form,dangerButton("Delete "+active.name,()->new AlertDialog.Builder(this)
                .setTitle("Delete "+active.name+"?")
                .setMessage("Delete this voice profile and all of its exact recordings?")
                .setPositiveButton("Delete",(d,w)->{
                    if(engine.voices.delete(active.id)){toast(active.name+" deleted.");voiceLibrary();}
                    else toast("Could not delete that profile.");
                }).setNegativeButton("Cancel",null).show()));
        }

        form.addView(text("COMPLETE LIBRARY · FOR ANOTHER VOICE",11,ACCENT,true));gap(form,5);
        form.addView(text("One button copies the entire CURRENT library: the original script plus every phrase you have added or replaced up to this point. Patrol Link also remembers that exact phrase order so a full WAV generated in another voice can be imported and mapped correctly.",12,MUTED,false));gap(form,8);

        form.addView(text("Saved complete template · "+engine.voices.completeTemplateCount()+
            " phrases · "+engine.voices.completeTemplateSource(),11,MUTED,false));gap(form,8);

        addCard(form,button("Load script text file (.txt)",true,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/octet-stream"});
            try{startActivityForResult(pick,REQ_SCRIPT_TEXT);}
            catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));
        addCard(form,button("Copy loaded script / template",false,()->{
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("Loaded recording script",engine.voices.loadedScriptText()));
            toast("Copied "+engine.voices.completeTemplateCount()+" phrases from the loaded template.");
        }));
        form.addView(text("For an edited or expanded master script: load the matching .txt file, then import its WAV. Loading text alone does not change any installed recordings. Keep the same phrase count and order in the text and audio. Optional pet names are recordings only; they are not enabled in alerts automatically.",12,MUTED,false));gap(form,8);

        addCard(form,button("Import complete-library WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_COMPLETE_LIBRARY);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));

        form.addView(text("ORIGINAL BUILT-IN BASE · RESET ONLY",11,MUTED,true));gap(form,5);
        form.addView(text("You normally will not need these controls. They use only Patrol Link's original built-in base list and do not include later add-ons.",11,MUTED,false));gap(form,8);

        addCard(form,button("Copy original built-in base script",false,()->{
            String script=engine.voices.exactPackScript(engine.recent(),engine.guards());
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText(active.name+" original built-in base script",script));
            toast("Original built-in base script copied.");
        }));

        addCard(form,button((engine.voices.ready()?"Replace":"Import")+" from original base WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        LinearLayout shell=vertical();
        EntireScriptCopyUi copy=new EntireScriptCopyUi(this,engine.voices);
        copy.setPadding(dp(20),dp(6),dp(20),0);
        shell.addView(copy,new LinearLayout.LayoutParams(-1,-2));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        voiceDialog=new AlertDialog.Builder(this).setTitle("Voice settings").setView(shell)
                .setPositiveButton("Close",null).create();
        voiceDialog.show();
        if(voiceDialog.getWindow()!=null)voiceDialog.getWindow().setLayout(-1,
                Math.round(getResources().getDisplayMetrics().heightPixels*.88f));
        if(engine.voices.tiffBusy())engine.voices.whenTiffFinished((ok,message)->{
            if(isFinishing()||isDestroyed()||voiceDialog==null||!voiceDialog.isShowing())return;
            voiceLibrary();
        });
    }

    private void showAddVoicePhrases(){
        VoiceManager.Profile active=engine.voices.activeProfile();
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        form.addView(text("ADD PHRASES TO "+active.name.toUpperCase(Locale.ROOT),11,ACCENT,true));gap(form,8);
        form.addView(text("Enter only what you need, one phrase per line. For example, if General Patrol is already recorded, adding only Bobsled Lane is enough — Patrol Link can join the two exact recordings at playback.",13,MUTED,false));gap(form,10);
        form.addView(text("Generate these lines in the same order with [pause 3] between them. You can import a single phrase or hundreds, then do another add-on later. Matching phrases replace only their old recording.",12,MUTED,false));gap(form,10);

        EditText phrases=new EditText(this);
        phrases.setTextColor(WHITE);phrases.setHintTextColor(MUTED);phrases.setTextSize(16);
        phrases.setHint("Bobsled Lane\\nBalmara Place\\nTradition Place\\nD Rogers 1");
        phrases.setGravity(Gravity.TOP);phrases.setMinLines(7);phrases.setMaxLines(14);
        phrases.setSingleLine(false);
        phrases.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        java.util.List<String> draft=engine.voices.addOnDraft();
        if(!draft.isEmpty())phrases.setText(android.text.TextUtils.join("\n",draft));
        form.addView(phrases,new LinearLayout.LayoutParams(-1,-2));gap(form,10);

        addCard(form,button("Copy add-on script",true,()->{
            try{
                java.util.List<String> list=engine.voices.parseAddOnPhrases(phrases.getText().toString());
                engine.voices.saveAddOnDraft(list);
                String script=engine.voices.addOnScript(list);
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText(active.name+" add-on exact voice script",script));
                toast(list.size()+" phrase"+(list.size()==1?"":"s")+" copied. Generate only this small add-on.");
            }catch(Exception e){toast(e.getMessage()==null?"Check the phrase list.":e.getMessage());}
        }));

        addCard(form,button("Import add-on WAV",true,()->{
            try{
                java.util.List<String> list=engine.voices.parseAddOnPhrases(phrases.getText().toString());
                engine.voices.saveAddOnDraft(list);
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
                startActivityForResult(pick,REQ_VOICE_ADDON);
            }catch(ActivityNotFoundException e){toast("No file picker is installed.");}
             catch(Exception e){toast(e.getMessage()==null?"Check the phrase list.":e.getMessage());}
        }));

        addCard(form,button("Clear add-on draft",false,()->{
            phrases.setText("");
            engine.voices.saveAddOnDraft(Collections.emptyList());
            toast("Add-on draft cleared. Installed recordings were not changed.");
        }));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Add exact phrases").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void addVoiceProfile(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Add voice profile")
            .setMessage("Create the profile, then import the complete-library WAV generated from the one-button complete script, or start with small add-on WAVs. Patrol Link preserves the complete template when you create this new voice.")
            .setView(name).setPositiveButton("Create",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            engine.voices.importTrainingAudio(null,name.getText().toString(),(ok,msg)->{
                toast(msg);if(ok){d.dismiss();voiceLibrary();}
            });
        }));
        d.show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        if(checkpointUi!=null && checkpointUi.onActivityResult(requestCode,resultCode,data)) return;
        super.onActivityResult(requestCode,resultCode,data);

        if(requestCode==REQ_VOICE_ADDON&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            final int count=engine.voices.addOnDraft().size();
            Toast.makeText(this,"Adding "+count+" "+name+" exact phrase"+(count==1?"":"s")+"…",Toast.LENGTH_LONG).show();
            engine.voices.importAddOn(data.getData(),(ok,message)->{
                toast(message);
                if(ok&&!isFinishing())voiceLibrary();
            });
            return;
        }

        if(requestCode==REQ_SCRIPT_TEXT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            engine.voices.importScriptTemplate(data.getData(),(ok,message)->{
                toast(message);
                if(!isFinishing())new AlertDialog.Builder(this)
                    .setTitle(ok?"Recording script loaded":"Script not loaded")
                    .setMessage(message)
                    .setPositiveButton("OK",(d,w)->{if(ok)voiceLibrary();}).show();
            });
            return;
        }
        if(requestCode==REQ_COMPLETE_LIBRARY&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            final android.net.Uri uri=data.getData();
            final int count=engine.voices.completeTemplateCount();
            final String source=engine.voices.completeTemplateSource();
            Runnable perform=()->{
                Toast.makeText(this,"Importing "+count+" exact "+name+" recordings…",Toast.LENGTH_LONG).show();
                engine.voices.importCompleteLibrary(uri,(ok,message)->{
                    toast(message);if(ok&&!isFinishing())voiceLibrary();
                });
            };
            if(engine.voices.ready()){
                new AlertDialog.Builder(this)
                    .setTitle("Replace "+name+" with complete library?")
                    .setMessage("This imports "+count+" phrases from the saved "+source+
                        " complete-library template and replaces only "+name+"'s current exact library.")
                    .setPositiveButton("Replace "+name,(d,w)->perform.run())
                    .setNegativeButton("Cancel",null).show();
            }else perform.run();
            return;
        }

        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            new AlertDialog.Builder(this)
                .setTitle("Replace full "+name+" library?")
                .setMessage("This is the full-base import. It replaces the current exact library. Use Add / replace exact phrases instead if you only want to add a few new recordings.")
                .setPositiveButton("Replace full library",(d,w)->{
                    Toast.makeText(this,"Importing full "+name+" base WAV…",Toast.LENGTH_LONG).show();
                    engine.voices.importExactPack(data.getData(),(ok,message)->{
                        toast(message);if(ok&&!isFinishing())voiceLibrary();
                    });
                })
                .setNegativeButton("Cancel",null).show();
            return;
        }

        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK){
            toast("Create/select a voice profile, then use Add / replace exact phrases.");
        }
    }

    private void diagnostics() {
        String report=engine.diagnostics()+(tabletDashboard==null?"":"\n\n"+tabletDashboard.diagnostics()); new AlertDialog.Builder(this).setTitle("Connection details").setMessage(report)
                .setPositiveButton("Copy details",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TRL Patrol Link diagnostics",report));toast("Connection details copied.");})
                .setNegativeButton("Close",null).show();
    }
    private void confirmExit() {
        new AlertDialog.Builder(this).setTitle("Exit TRL Patrol Link?")
                .setMessage("Exit is the only control that deliberately signs Patrol Link out. It stops monitoring and local GPS, marks a patrol off duty, clears the Silvertracker session and any remembered Silvertracker login, then closes the app. Closing the app normally or swiping it from Recents does not clear the session.")
                .setPositiveButton("Exit & sign out",(d,w)->{
                    FleetRelay.setDuty(this,false);
                    LocalGpsService.stop(this);
                    stopService(new Intent(this,MonitorService.class));
                    engine.exitAndClear(()->runOnUiThread(this::finishAndRemoveTask));
                }).setNegativeButton("Cancel",null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("TRL Patrol Link")
                .setMessage("The monitor browser stays attached behind the dashboard. Patrol Link normally requests an uncached monitor page every 10 seconds and waits for readable rows. Requests never overlap; slow/error states back off to 15 or 30 seconds. Automatic polling pauses while the Silvertracker screen is open so manual page navigation is not interrupted. A blank loading shell is never marked as a successful refresh. Timed-out attempts are cancelled and retried without deleting cookies.\n\nIf Silvertracker invalidates the server-side session, Remember Silvertracker login can securely re-authenticate this device and return to Issue Monitor. Enable it on the Silvertracker screen and sign in normally once. If it is off, Patrol Link shows Sign-in required without clearing cached activity.\n\nTap any guard card to see that guard's recent Issue Monitor entries. The main dashboard stays compact.\n\nEach selectable voice profile uses one exact WAV generated by your voice website from Patrol Link's fixed canonical script. The canonical phrase list is identical on every phone, so the same WAV can be transferred between devices without changing the expected phrase count or order. Missing phrases stay silent rather than using another voice.\n\nThe Android Auto screen remains a native, read-only activity display. The data shown is Issue Monitor history, not live GPS.")
                .setPositiveButton("Close",null).show();
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onResume() { if(browserMode)MonitorFilterUi.resume(this);  super.onResume();if(tabletDashboard!=null)tabletDashboard.resume();if(gpsUi!=null)gpsUi.resume();if(attachedWeb!=null){attachedWeb.onResume();attachedWeb.resumeTimers();}engine.appVisible=true;engine.browserVisible=browserMode;ensureMonitoring();timer.removeCallbacks(tick);timer.post(tick); }
    @Override public void onPause() { MonitorFilterUi.pause(this);  if(tabletDashboard!=null)tabletDashboard.pause();if(gpsUi!=null)gpsUi.pause();engine.appVisible=false;engine.browserVisible=false;timer.removeCallbacks(tick);super.onPause(); }
    @Override public void onDestroy() { MonitorFilterUi.unbind(this);  if(tabletDashboard!=null)tabletDashboard.close();if(gpsUi!=null)gpsUi.close();engine.remove(this);timer.removeCallbacks(tick);engine.appVisible=false;if(browserMode)engine.browserVisible=false;detachWeb();super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(gpsUi==null)return;
        if(request==72)gpsUi.offerFirstRun();else gpsUi.permissions(request);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_patrol_gps",false)&&gpsUi!=null)gpsUi.show();}
    @Override public void onBackPressed() {
        if(browserMode){showDashboard();engine.resumeLiveFeed();}
        else moveTaskToBack(true);
    }
}
