from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 150','versionCode 151')
rep(root/'app/build.gradle',"versionName '1.1.40'","versionName '1.1.41'")

p=java/'CheckpointProximityUi.java'; s=p.read_text()

# Display-only proximity state.
s=s.replace('    private String activeId="",pendingPhotoId="";\n    private AlertDialog scanDialog;\n    private String scanDialogId="";',
'''    private String activeId="",pendingPhotoId="";
    private AlertDialog scanDialog;
    private String scanDialogId="";
    private String closedId="";''',1)

# Remove completion state from nearest selection; always choose physically closest mapped checkpoint.
old='''        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped()||completed(c.id))continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            activeId="";
            showNone(activeRoute().stream().noneMatch(Checkpoint::mapped)?"No checkpoint locations registered for this patrol":"All mapped checkpoints completed for this lap");
            return;
        }

        activeId=best.id;
        boolean scanRange=bestDist<=best.radius;
        title.setText(scanRange?"CHECKPOINT IN SCAN RANGE":"NEXT CHECKPOINT");
        distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        complete.setVisibility(View.GONE);
        if(photo!=null)photo.setVisibility(View.GONE);
        if(scanRange){
            hint.setText("Full checkpoint QR is open for scanning. Complete it after the SilverTrack phone scans the QR.");
            showScanDialog(best,bestDist);
        }else{
            hint.setText("Live checkpoint GPS · full QR opens automatically inside the configured "+Math.round(best.radius)+" m scan range.");
        }'''
new='''        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped())continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            activeId="";
            showNone("No checkpoint locations registered for this patrol");
            return;
        }

        // A different physically closer checkpoint always takes over immediately.
        if(!best.id.equals(activeId)){
            activeId=best.id;
            if(scanDialog!=null&&scanDialog.isShowing()){
                scanDialog.dismiss();
                scanDialog=null;
                scanDialogId="";
            }
            if(!best.id.equals(closedId)) closedId="";
        }

        boolean scanRange=bestDist<=best.radius;
        title.setText(scanRange?"CHECKPOINT IN SCAN RANGE":"CLOSEST CHECKPOINT");
        distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        complete.setVisibility(View.GONE);
        if(photo!=null)photo.setVisibility(View.GONE);

        // Re-arm a previously closed QR only after the vehicle has left its configured scan radius.
        if(best.id.equals(closedId) && bestDist>best.radius){
            closedId="";
        }

        if(scanRange){
            hint.setText("Full checkpoint QR opens automatically. Scan it with SilverTrack, then tap CLOSE.");
            if(!best.id.equals(closedId)) showScanDialog(best,bestDist);
        }else{
            hint.setText("Live checkpoint GPS · full QR opens automatically inside the configured "+Math.round(best.radius)+" m scan range.");
        }'''
if old not in s: raise RuntimeError('v149 tick block missing')
s=s.replace(old,new,1)

# Remove Patrol Link completion semantics entirely.
old='''    private boolean completed(String id){
        long done=prefs.getLong("done_"+id,0);
        if(done<=0)return false;
        return lapEpoch<=0 || done>=lapEpoch;
    }'''
new='''    private boolean completed(String id){ return false; }'''
if old not in s: raise RuntimeError('completed method missing')
s=s.replace(old,new,1)

# Full-screen dialog is display-only and closes silently.
old='''    private void showScanDialog(Checkpoint c,float metres){
        if(c==null||completed(c.id)){
            if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();
            scanDialog=null;scanDialogId="";
            return;
        }
        if(scanDialog!=null&&scanDialog.isShowing()&&c.id.equals(scanDialogId))return;
        if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();

        LinearLayout shell=new LinearLayout(activity);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(14),dp(12),dp(14),dp(14));
        TextView h=text(c.name+" · "+Math.round(metres)+" m",20,0xffeef4f9,true);h.setGravity(Gravity.CENTER);shell.addView(h);shell.addView(gap(10));

        Bitmap b=bitmapFor(c);
        if(b!=null){
            ImageView qr=new ImageView(activity);qr.setAdjustViewBounds(true);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setImageBitmap(b);
            int maxH=Math.round(activity.getResources().getDisplayMetrics().heightPixels*0.62f);
            shell.addView(qr,new LinearLayout.LayoutParams(-1,maxH));
        }else{
            TextView missing=text("No checkpoint photo is attached to this checkpoint.",16,0xffffc266,true);missing.setGravity(Gravity.CENTER);missing.setPadding(0,dp(40),0,dp(40));shell.addView(missing);
        }

        shell.addView(gap(12));
        Button done=button("CHECKPOINT COMPLETE",()->{
            long now=System.currentTimeMillis();
            prefs.edit().putLong("done_"+c.id,now).commit();
            activeId="";scanDialogId="";
            AlertDialog closing=scanDialog;
            scanDialog=null;
            if(closing!=null&&closing.isShowing())closing.dismiss();
            Toast.makeText(activity,c.name+" completed for this Patrol Link lap.",Toast.LENGTH_SHORT).show();
            // Let Android fully remove the modal before the next proximity pass.
            new Handler(Looper.getMainLooper()).postDelayed(this::tick,350L);
        });
        done.setTextSize(18);done.setMinHeight(dp(64));shell.addView(done,new LinearLayout.LayoutParams(-1,dp(64)));

        scanDialog=new AlertDialog.Builder(activity).setView(shell).create();
        scanDialog.setCancelable(false);scanDialogId=c.id;scanDialog.show();
        if(scanDialog.getWindow()!=null)scanDialog.getWindow().setLayout(-1,-2);
    }'''
new='''    private void showScanDialog(Checkpoint c,float metres){
        if(c==null)return;
        if(scanDialog!=null&&scanDialog.isShowing()&&c.id.equals(scanDialogId))return;
        if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();

        LinearLayout shell=new LinearLayout(activity);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(14),dp(12),dp(14),dp(14));
        TextView h=text(c.name+" · "+Math.round(metres)+" m",20,0xffeef4f9,true);h.setGravity(Gravity.CENTER);shell.addView(h);shell.addView(gap(10));

        Bitmap b=bitmapFor(c);
        if(b!=null){
            ImageView qr=new ImageView(activity);qr.setAdjustViewBounds(true);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setImageBitmap(b);
            int maxH=Math.round(activity.getResources().getDisplayMetrics().heightPixels*0.62f);
            shell.addView(qr,new LinearLayout.LayoutParams(-1,maxH));
        }else{
            TextView missing=text("No checkpoint photo is attached to this checkpoint.",16,0xffffc266,true);missing.setGravity(Gravity.CENTER);missing.setPadding(0,dp(40),0,dp(40));shell.addView(missing);
        }

        shell.addView(gap(12));
        Button close=button("CLOSE",()->{
            closedId=c.id;
            scanDialogId="";
            AlertDialog closing=scanDialog;
            scanDialog=null;
            if(closing!=null&&closing.isShowing())closing.dismiss();
        });
        close.setTextSize(18);close.setMinHeight(dp(64));shell.addView(close,new LinearLayout.LayoutParams(-1,dp(64)));

        scanDialog=new AlertDialog.Builder(activity).setView(shell).create();
        scanDialog.setCancelable(false);scanDialogId=c.id;scanDialog.show();
        if(scanDialog.getWindow()!=null)scanDialog.getWindow().setLayout(-1,-2);
    }'''
if old not in s: raise RuntimeError('scan dialog block missing')
s=s.replace(old,new,1)

# No reset/completion storage needed for new laps; keep manager button harmless and clear only UI suppression.
old='''    public void newLap(){lapEpoch=System.currentTimeMillis();SharedPreferences.Editor e=prefs.edit().putLong("lap_epoch",lapEpoch);for(Checkpoint c:checkpoints)e.remove("done_"+c.id);e.apply();activeId="";scanDialogId="";if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();scanDialog=null;tick();Toast.makeText(activity,"Checkpoint proximity reset for a new lap.",Toast.LENGTH_SHORT).show();}'''
new='''    public void newLap(){lapEpoch=System.currentTimeMillis();prefs.edit().putLong("lap_epoch",lapEpoch).apply();activeId="";closedId="";scanDialogId="";if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();scanDialog=null;tick();}'''
if old not in s: raise RuntimeError('newLap block missing')
s=s.replace(old,new,1)

# Retire unused old dashboard completion handler so no accidental local state changes occur.
old='''    private void completeActive(){if(activeId.isEmpty())return;prefs.edit().putLong("done_"+activeId,System.currentTimeMillis()).apply();activeId="";tick();Toast.makeText(activity,"Checkpoint cleared from this Patrol Link lap.",Toast.LENGTH_SHORT).show();}'''
new='''    private void completeActive(){ }'''
if old in s: s=s.replace(old,new,1)

p.write_text(s)
print('Applied v1.1.41: display-only closest-checkpoint QR overlay, CLOSE only, no local completion/tally, and nearest checkpoint switches dynamically.')
