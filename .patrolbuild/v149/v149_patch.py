from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 148','versionCode 149')
rep(root/'app/build.gradle',"versionName '1.1.38'","versionName '1.1.39'")

p=java/'CheckpointProximityUi.java'; s=p.read_text()

s=s.replace('private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f, GATE_TRIGGER=100f, GATE_CLEAR=180f;',
            'private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f;',1)
s=s.replace('    private static final String CENTSYS_PACKAGE="com.centurionsystems.mycentsysremote";\n','',1)
s=s.replace('    private String activeId="",pendingPhotoId="",gateSuppressedId="";\n    private boolean gateDialogOpen;',
            '    private String activeId="",pendingPhotoId="";\n    private AlertDialog scanDialog;\n    private String scanDialogId="";',1)

# Remove gate proximity call.
s=s.replace('''        maybeGatePrompt(lat,lon);

        Checkpoint best=null;float bestDist=Float.MAX_VALUE;''',
            '''        Checkpoint best=null;float bestDist=Float.MAX_VALUE;''',1)

# Respect the supervisor-set scan radius and auto-open the full checkpoint image.
old='''        activeId=best.id;
        boolean scanRange=bestDist<=50f;
        title.setText(scanRange?"CHECKPOINT IN SCAN RANGE":"NEXT CHECKPOINT");
        distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        if(scanRange){
            hint.setText("Scan the physical SilverTrack QR code, then tap SCAN COMPLETE. Only the SilverTrack scan changes the tally.");
            complete.setVisibility(View.VISIBLE);
            showPhoto(best);
        }else{
            hint.setText("Live checkpoint GPS · QR/photo appears automatically at 50 metres.");
            complete.setVisibility(View.GONE);
            if(photo!=null)photo.setVisibility(View.GONE);
        }'''
new='''        activeId=best.id;
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
if old not in s: raise RuntimeError('v146 scan-range block missing')
s=s.replace(old,new,1)

# Dashboard card should remain status-only during patrol; supervisor can still open manager outside scan modal.
s=s.replace('card.setOnClickListener(v->showManager());tick();return card;',
            'card.setOnClickListener(v->{if(scanDialog==null||!scanDialog.isShowing())showManager();});tick();return card;',1)

# Rename manager wording from generic trigger to explicit scan range.
s=s.replace(' · trigger %.0f m',' · scan range %.0f m')
s=s.replace('"Location not registered · trigger "+Math.round(c.radius)+" m','"Location not registered · scan range "+Math.round(c.radius)+" m')
s=s.replace('radius.setHint("Trigger metres");','radius.setHint("Scan range metres");',1)

# Insert full-image scan modal before photo import methods.
anchor='''    private void choosePhoto(Checkpoint c){'''
modal='''    private void showScanDialog(Checkpoint c,float metres){
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
        Button done=button("CHECKPOINT COMPLETE",()->{
            prefs.edit().putLong("done_"+c.id,System.currentTimeMillis()).apply();
            activeId="";scanDialogId="";
            if(scanDialog!=null)scanDialog.dismiss();
            scanDialog=null;
            Toast.makeText(activity,c.name+" completed for this Patrol Link lap.",Toast.LENGTH_SHORT).show();
            tick();
        });
        done.setTextSize(18);done.setMinHeight(dp(64));shell.addView(done,new LinearLayout.LayoutParams(-1,dp(64)));

        scanDialog=new AlertDialog.Builder(activity).setView(shell).create();
        scanDialog.setCancelable(false);scanDialogId=c.id;scanDialog.show();
        if(scanDialog.getWindow()!=null)scanDialog.getWindow().setLayout(-1,-2);
    }

    private void choosePhoto(Checkpoint c){'''
if anchor not in s: raise RuntimeError('choosePhoto anchor missing')
s=s.replace(anchor,modal,1)

# Dismiss scan modal when a new lap is explicitly started.
s=s.replace('''    public void newLap(){lapEpoch=System.currentTimeMillis();SharedPreferences.Editor e=prefs.edit().putLong("lap_epoch",lapEpoch);for(Checkpoint c:checkpoints)e.remove("done_"+c.id);e.apply();activeId="";tick();Toast.makeText(activity,"Checkpoint proximity reset for a new lap.",Toast.LENGTH_SHORT).show();}''',
'''    public void newLap(){lapEpoch=System.currentTimeMillis();SharedPreferences.Editor e=prefs.edit().putLong("lap_epoch",lapEpoch);for(Checkpoint c:checkpoints)e.remove("done_"+c.id);e.apply();activeId="";scanDialogId="";if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();scanDialog=null;tick();Toast.makeText(activity,"Checkpoint proximity reset for a new lap.",Toast.LENGTH_SHORT).show();}''',1)

# Remove all MyCentsys/gate behaviour.
start=s.find('    private void maybeGatePrompt(double lat,double lon){')
end=s.find('    private static int defaultOrder(String id)',start)
if start<0 or end<0: raise RuntimeError('gate methods block missing')
s=s[:start]+s[end:]
s=s.replace('supervisor-managed checkpoint metadata/photos and optional MyCentsys launch prompts.',
            'supervisor-managed checkpoint metadata/photos and full-screen scan prompts.')
p.write_text(s)

# Prevent duplicated place wording such as "General Patrol Impeccable. Impeccable BC."
p=java/'SpeechRules.java'; s=p.read_text()
old='''        if(location.isEmpty())append(b,"Location not supplied");
        else if(!AnnouncementText.containsLocation(issue,location))append(b,location);'''
new='''        if(location.isEmpty())append(b,"Location not supplied");
        else {
            String issuePlace=key(issue).replaceAll("\\b(general patrol|guard patrol|security patrol|body corporate|bc|rbc)\\b"," ").replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
            String locationPlace=key(location).replaceAll("\\b(body corporate|bc|rbc)\\b"," ").replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
            boolean samePlace=!issuePlace.isEmpty()&&!locationPlace.isEmpty()&&(issuePlace.contains(locationPlace)||locationPlace.contains(issuePlace));
            if(!samePlace&&!AnnouncementText.containsLocation(issue,location))append(b,location);
        }'''
if old not in s: raise RuntimeError('SpeechRules location append anchor missing')
s=s.replace(old,new,1)
p.write_text(s)

print('Applied v1.1.39: removed all gate/MyCentsys features, configurable scan radius opens full checkpoint photo automatically, and duplicate place speech is suppressed.')
