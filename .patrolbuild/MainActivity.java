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
    private static final int BG=0xff080d12, PANEL=0xff121d27, LINE=0xff263746, WHITE=0xffeef4f9, MUTED=0xff9daebb, ACCENT=0xff70d7c4, AMBER=0xffe4b771, RED=0xffe68181;
    private PatrolEngine engine;
    private LinearLayout root, dashboard;
    private TextView stateText, detailText, readText, countdownText;
    private final TextView[] guardTitles = new TextView[3], activities = new TextView[3], activityMeta = new TextView[3], flags = new TextView[3];
    private final LinearLayout[] guardCards = new LinearLayout[3];
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean browserMode;
    private WebView attachedWeb;
    private final Runnable tick = new Runnable() { public void run() { refresh(); timer.postDelayed(this, 1000); }};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = ((PatrolApp)getApplication()).engine();
        engine.add(this); engine.appVisible=true;
        showDashboard(); ensureMonitoring();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);
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
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary ? BG : WHITE);
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
        detachWeb(); root=vertical(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;});
        setContentView(root); root.requestApplyInsets();
    }
    private void showDashboard() {
        browserMode=false; engine.browserVisible=false; base();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        dashboard=vertical(); dashboard.setPadding(dp(22),dp(24),dp(22),dp(20)); scroll.addView(dashboard);
        TextView eyebrow=text("RONIN  /  FIELD SYSTEMS",11,ACCENT,true); eyebrow.setLetterSpacing(.14f); dashboard.addView(eyebrow); gap(dashboard,10);
        dashboard.addView(text("Patrol Link",34,WHITE,true)); gap(dashboard,5);
        dashboard.addView(text("LIVE SHIFT ACTIVITY",11,MUTED,true)); gap(dashboard,22);

        LinearLayout status=card(); stateText=text("",14,ACCENT,true); status.addView(stateText); gap(status,8);
        detailText=text("",13,MUTED,false); status.addView(detailText); gap(status,14);
        readText=text("",12,WHITE,false); status.addView(readText); gap(status,5);
        countdownText=text("",12,MUTED,false); status.addView(countdownText); addCard(dashboard,status);

        for(int i=0;i<3;i++) {
            final int index=i;
            LinearLayout c=card(); guardCards[i]=c; c.setClickable(true); c.setFocusable(true); c.setOnClickListener(v->showGuardHistory(index));
            guardTitles[i]=text("",12,ACCENT,true); c.addView(guardTitles[i]); gap(c,10);
            activities[i]=text("Waiting for activity",22,WHITE,true); c.addView(activities[i]); gap(c,8);
            activityMeta[i]=text("",13,MUTED,false); c.addView(activityMeta[i]); gap(c,8);
            flags[i]=text("",11,AMBER,true); c.addView(flags[i]); gap(c,8);
            c.addView(text("Tap for this guard's recent activity",11,MUTED,false));
            addCard(dashboard,c);
        }

        buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));
        buttonRow(dashboard,button("Diagnostics",false,this::diagnostics),dangerButton("Exit / sign out",this::confirmExit));
        dashboard.addView(text("The main screen only shows each selected guard's latest Issue Monitor activity. Tap a guard card to view that guard's recent entries.",12,MUTED,false)); gap(dashboard,12);
        TextView help=text("Patrol Link information",13,ACCENT,true); help.setPadding(0,dp(8),0,dp(8)); help.setOnClickListener(v->help()); dashboard.addView(help);
        refresh();
    }
    private void showGuardHistory(int index) {
        String guard=engine.guards().get(index);
        List<Observation> history=engine.recentForGuard(guard);
        LinearLayout list=vertical(); list.setPadding(dp(16),dp(8),dp(16),dp(8));
        if(history.isEmpty()) {
            list.addView(text("No recent Issue Monitor entries have been cached for "+guard+" yet.",14,MUTED,false));
        } else {
            int count=Math.min(15,history.size());
            for(int i=0;i<count;i++) {
                Observation o=history.get(i); LinearLayout c=card();
                c.addView(text(o.activity(),16,WHITE,true)); gap(c,5);
                String time=o.recordedAt>0?DateTimeFormatter.ofPattern("HH:mm:ss · d MMM").format(Instant.ofEpochMilli(o.recordedAt).atZone(engine.zone())):o.recordedText;
                c.addView(text(o.locationLine()+" · "+time,12,MUTED,false)); gap(c,4);
                c.addView(text("Issue "+o.id,11,MUTED,false)); addCard(list,c);
            }
        }
        ScrollView scroll=new ScrollView(this); scroll.addView(list);
        new AlertDialog.Builder(this).setTitle(guard+" · Recent activity").setView(scroll).setPositiveButton("Close",null).show();
    }
    private void showBrowser() {
        browserMode=true; engine.browserVisible=true; base();
        LinearLayout toolbar=vertical(); toolbar.setPadding(dp(12),dp(12),dp(12),0);
        toolbar.addView(text("SILVERTRACKER · SESSION",12,ACCENT,true)); gap(toolbar,8);
        buttonRow(toolbar,button("Dashboard",false,()->{ engine.inspectNow(); timer.postDelayed(this::showDashboard, 700); }),button("Refresh now",true,engine::refreshMonitor));
        root.addView(toolbar);
        TextView note=text("If Silvertracker asks you to sign in, sign in manually once. Patrol Link keeps that WebView session and then uses Silvertracker's own Update control every 30 seconds instead of repeatedly reopening the login URL.",12,MUTED,false);
        note.setPadding(dp(14),0,dp(14),dp(10)); root.addView(note);
        attachedWeb=engine.webView(); if(attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb);
        root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));
        if(attachedWeb.getUrl()==null || "about:blank".equals(attachedWeb.getUrl())) engine.openMonitor();
    }
    private void detachWeb() { if(attachedWeb != null && attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb); attachedWeb=null; }

    @Override public void changed() { refresh(); }
    private void refresh() {
        if(browserMode || stateText==null) return;
        long now=System.currentTimeMillis();
        stateText.setText(engine.sourceStatus()); stateText.setTextColor(engine.healthy()?ACCENT:AMBER); detailText.setText(engine.detail);
        String last=engine.lastRead==0?"No successful refresh yet":DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(engine.lastRead).atZone(engine.zone()));
        readText.setText("Last successful refresh: "+last);
        long wait=Math.max(0,(engine.nextCheckElapsed-SystemClock.elapsedRealtime()+999)/1000);
        countdownText.setText("SIGN_IN_REQUIRED".equals(engine.state)?"Automatic refresh paused until manual sign-in":engine.running?"Automatic refresh: 30 seconds · next check in "+wait+"s":"Monitor stopped");
        List<String> guards=engine.guards();
        for(int i=0;i<3;i++) {
            String guard=guards.get(i), key=FeedReducer.key(guard); Observation o=engine.latest.get(key); guardTitles[i].setText(guard);
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
        LinearLayout form=vertical();form.setPadding(dp(22),dp(10),dp(22),dp(10)); EditText[] fields=new EditText[3];
        for(int i=0;i<3;i++) fields[i]=input(form,"GUARD "+(i+1)+" · EXACT CREATED BY ID",engine.guards().get(i),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText zone=input(form,"SITE TIME ZONE",engine.zone().getId(),InputType.TYPE_CLASS_TEXT);
        EditText old=input(form,"FLAG ACTIVITY OLDER THAN (MINUTES)",String.valueOf(engine.oldMinutes()),InputType.TYPE_CLASS_NUMBER);
        CheckBox voice=new CheckBox(this);voice.setText("Speak new activity (installed offline voice)");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",false));form.addView(voice);
        gap(form,10);form.addView(text("All Issue Monitor types are included. Saving guard changes immediately refreshes the feed if the current Silvertracker session is signed in.",12,MUTED,false));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Guard settings").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try {
                Set<String> unique=new HashSet<>(); for(EditText field:fields) { String value=field.getText().toString().trim(); if(!value.matches("[A-Za-z0-9._ -]{1,40}") || !unique.add(FeedReducer.key(value))) throw new IllegalArgumentException("Enter three different guard IDs, exactly as shown in Created By."); }
                String zoneText=zone.getText().toString().trim();ZoneId.of(zoneText); int minutes=Integer.parseInt(old.getText().toString());if(minutes<1 || minutes>240) throw new IllegalArgumentException("Use an age threshold between 1 and 240 minutes.");
                SharedPreferences.Editor edit=engine.prefs.edit(); for(int i=0;i<3;i++) edit.putString("guard"+i,fields[i].getText().toString().trim());
                edit.putString("zone",zoneText).putInt("oldMinutes",minutes).putBoolean("patrolOnly",false).putBoolean("voice",voice.isChecked()).apply();
                engine.latest.clear();engine.present.clear();engine.lastRead=engine.lastReadElapsed=0; engine.status("CHECKING","Guard list updated. Refreshing Silvertracker…"); engine.refreshMonitor(); dialog.dismiss();refresh();
            } catch(Exception ex) { toast(ex.getMessage()==null?"Check the settings.":ex.getMessage()); }
        }));dialog.show();
    }
    private void diagnostics() {
        String report=engine.diagnostics(); new AlertDialog.Builder(this).setTitle("Connection details").setMessage(report)
                .setPositiveButton("Copy details",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Patrol Link diagnostics",report));toast("Connection details copied.");})
                .setNegativeButton("Close",null).show();
    }
    private void confirmExit() {
        new AlertDialog.Builder(this).setTitle("Exit Patrol Link?")
                .setMessage("Exit is the only control that deliberately signs Patrol Link out. It stops monitoring, clears the app's Silvertracker cookies/session and closes the app. Closing the app normally or swiping it from Recents does not clear the session.")
                .setPositiveButton("Exit & sign out",(d,w)->{
                    stopService(new Intent(this,MonitorService.class));
                    engine.exitAndClear(()->runOnUiThread(this::finishAndRemoveTask));
                }).setNegativeButton("Cancel",null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Patrol Link")
                .setMessage("After a successful Silvertracker sign-in, Patrol Link keeps the same WebView cookie/session and refreshes Issue Monitor every 30 seconds using Silvertracker's own Update control. It no longer reopens the login URL every 30 seconds.\n\nIf Silvertracker itself invalidates the server-side session, Patrol Link stops the retry loop and shows Sign-in required. Open Silvertracker, sign in once, and monitoring resumes without clearing your cached activity.\n\nTap any guard card to see that guard's recent Issue Monitor entries. The main dashboard stays compact.\n\nThe Android Auto screen remains a native, read-only activity display. The data shown is Issue Monitor history, not live GPS.")
                .setPositiveButton("Close",null).show();
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onResume() { super.onResume();engine.appVisible=true;engine.browserVisible=browserMode;ensureMonitoring();timer.removeCallbacks(tick);timer.post(tick); }
    @Override public void onPause() { engine.appVisible=false;engine.browserVisible=false;timer.removeCallbacks(tick);super.onPause(); }
    @Override public void onDestroy() { engine.remove(this);timer.removeCallbacks(tick);engine.appVisible=false;if(browserMode)engine.browserVisible=false;detachWeb();super.onDestroy(); }
    @Override public void onBackPressed() { if(browserMode)showDashboard();else moveTaskToBack(true); }
}
