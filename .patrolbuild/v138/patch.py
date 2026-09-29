from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]);j=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent

def rep(s,old,new):
    if old not in s:raise RuntimeError('v138 anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 137','versionCode 138');s=rep(s,"versionName '1.1.27'","versionName '1.1.28'");p.write_text(s)
shutil.copyfile(here/'BundledTiff.java',j/'BundledTiff.java')
p=j/'VoiceManager.java';s=p.read_text()
s=rep(s,'    private List<String> canonicalCache;', '''    private List<String> canonicalCache;
    private final boolean bundledTiffAvailable;
    private volatile String tiffStatus="Tiff is not included in this build";
    private boolean tiffBusy;
    private final List<ImportCallback> tiffCallbacks=new ArrayList<>();''')
s=rep(s,'        migrateProfiles();\n        refreshStatus();', '''        migrateProfiles();
        refreshStatus();
        bundledTiffAvailable=BundledTiff.available(this.context);
        if(bundledTiffAvailable){
            tiffStatus=tiffReady()?"Tiff ready — included recording":"Tiff recording included — preparing setup";
            if(!prefs.getBoolean(BundledTiff.DONE_PREF,false))main.post(()->prepareBundledTiff(null));
        }''')
anchor='    public void setActive(String id){'
methods=r'''    public boolean hasBundledTiff(){return bundledTiffAvailable;}
    public String tiffStatus(){return tiffStatus;}
    public boolean tiffBusy(){return tiffBusy;}
    public boolean tiffReady(){return !BundledTiff.readyId(context,prefs).isEmpty();}
    public void selectTiff(){String id=BundledTiff.readyId(context,prefs);if(!id.isEmpty())setActive(id);}
    private interface TiffSource { InputStream open() throws Exception; }

    public void whenTiffFinished(ImportCallback callback){
        if(callback==null)return;
        if(tiffBusy)tiffCallbacks.add(callback);
        else deliver(callback,tiffReady(),tiffStatus);
    }
    public void prepareBundledTiff(ImportCallback callback){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->prepareBundledTiff(callback));return;}
        if(!bundledTiffAvailable){deliver(callback,false,"This APK does not contain the Tiff audio bundle.");return;}
        runTiffSetup(()->context.getAssets().open(BundledTiff.ASSET),callback);
    }
    /** Test seam uses the same installer and registration path with a local fixture. */
    void prepareTiffFixture(File archive,ImportCallback callback){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->prepareTiffFixture(archive,callback));return;}
        runTiffSetup(()->new FileInputStream(archive),callback);
    }
    private void runTiffSetup(TiffSource source,ImportCallback callback){
        if(callback!=null)tiffCallbacks.add(callback);
        if(tiffBusy)return;
        tiffBusy=true;tiffStatus="Preparing Tiff — existing voices remain unchanged";
        files.execute(()->{
            try(InputStream in=source.open()){
                BundledTiff.Result result=BundledTiff.install(context,prefs,in,text->tiffStatus=text);
                main.post(()->{
                    try{
                        BundledTiff.register(prefs,result);
                        refreshStatus();finishTiff(true,"Tiff ready — "+result.count+" recordings included. Select Tiff to use her.");
                    }catch(Exception e){finishTiff(false,safe(e));}
                });
            }catch(Exception e){String error=safe(e);main.post(()->finishTiff(false,error));}
        });
    }
    private void finishTiff(boolean ok,String message){
        tiffBusy=false;tiffStatus=message;
        List<ImportCallback> callbacks=new ArrayList<>(tiffCallbacks);tiffCallbacks.clear();
        for(ImportCallback callback:callbacks)deliver(callback,ok,message);
    }

    public void setActive(String id){'''
s=rep(s,anchor,methods)
s=rep(s,'"\\nVoice status: "+status+', '"\\nBundled Tiff: "+tiffStatus+\n                "\\nVoice status: "+status+')
p.write_text(s)
p=j/'MainActivity.java';s=p.read_text()
anchor='''        for(VoiceManager.Profile profile:profiles){'''
new=r'''        if(engine.voices.hasBundledTiff()){
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

        for(VoiceManager.Profile profile:profiles){'''
s=rep(s,anchor,new)
anchor='''        if(voiceDialog.getWindow()!=null)voiceDialog.getWindow().setLayout(-1,
                Math.round(getResources().getDisplayMetrics().heightPixels*.88f));'''
new=anchor+'''
        if(engine.voices.tiffBusy())engine.voices.whenTiffFinished((ok,message)->{
            if(isFinishing()||isDestroyed()||voiceDialog==null||!voiceDialog.isShowing())return;
            voiceLibrary();
        });'''
s=rep(s,anchor,new);p.write_text(s)
shutil.copyfile(here/'BundledTiffTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/BundledTiffTest.java')
assert 'Use Tiff' in s
assert 'versionCode 138' in (root/'app/build.gradle').read_text()
