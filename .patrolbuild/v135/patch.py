from pathlib import Path
import sys, shutil, re
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v135 anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle'; s=p.read_text(); s=rep(s,'versionCode 134','versionCode 135'); s=rep(s,"versionName '1.1.24'","versionName '1.1.25'"); p.write_text(s)

# The former universal 250-phrase ceiling accidentally applied to full libraries.
p=java/'ExactPhrasePack.java'; s=p.read_text()
s=rep(s,'validatePhraseList(phrases,3)','validatePhraseList(phrases,1,false)')
s=rep(s,'validatePhraseList(phrases,1)','validatePhraseList(phrases,1,true)')
s=rep(s,'validatePhraseList(List<String> phrases,int minimum)','validatePhraseList(List<String> phrases,int minimum,boolean addition)')
s=rep(s,'if(clean.size()>250)throw new IOException("Use at most 250 phrases in one add-on WAV. You can import as many add-on WAVs as needed.");','if(clean.size()>(addition?250:2000))throw new IOException(addition?"Use at most 250 phrases per add-on WAV.":"Use at most 2000 phrases per full-library WAV.");')
p.write_text(s)

(java/'VoiceScriptText.java').write_text(r'''package au.com.roningroup.patrollink;

import java.io.IOException;
import java.util.*;

/** Strict, order-preserving manifest for a user-supplied recording script. */
public final class VoiceScriptText {
    private VoiceScriptText() {}
    public static List<String> parse(String raw) throws IOException {
        if(raw==null || raw.length()>524288) throw new IOException("Choose a UTF-8 script smaller than 512 KB.");
        String text=raw.startsWith("\uFEFF")?raw.substring(1):raw;
        text=text.replaceAll("(?i)\\[pause\\s+3\\]", "\n");
        ArrayList<String> out=new ArrayList<>();
        HashSet<String> seen=new HashSet<>();
        for(String line:text.split("\\r?\\n")) {
            String phrase=line.replaceAll("\\s+"," ").trim();
            if(phrase.isEmpty())continue;
            if(phrase.contains("[") || phrase.contains("]")) throw new IOException("Only [pause 3] markers are supported. Remove headings or other voice instructions from the phrase list.");
            if(phrase.length()>300)throw new IOException("Each phrase must be no more than 300 characters.");
            if(!seen.add(ExactPhrasePack.normalize(phrase)))throw new IOException("Duplicate phrase: "+phrase+". Remove the duplicate before generating the WAV.");
            out.add(phrase);
        }
        if(out.isEmpty())throw new IOException("The script has no phrases.");
        if(out.size()>2000)throw new IOException("Use at most 2000 phrases in one full-library script.");
        return out;
    }
    public static String format(List<String> phrases) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<phrases.size();i++) {
            if(i>0)out.append("[pause 3]\n\n");
            out.append(phrases.get(i)).append('\n');
        }
        return out.toString();
    }
}
''')

# Complete-word aliases apply only when the original exact recording is absent.
# Existing libraries still play their original recordings without being rewritten.
(java/'RecordedTitleAliases.java').write_text(r'''package au.com.roningroup.patrollink;
import java.util.*;
public final class RecordedTitleAliases {
    private static final Map<String,String> aliases=new HashMap<>();
    static {
        aliases.put("general patrol - balmara pla","General Patrol - Balmara Place");
        aliases.put("general patrol - bobsled lan","General Patrol - Bobsled Lane");
        aliases.put("general patrol - ceil circui","General Patrol - Ceil Circuit");
        aliases.put("general patrol - christina b","General Patrol - Christina BC");
        aliases.put("general patrol - northern ba","General Patrol - Northern Bay");
        aliases.put("general patrol - sovereign b","General Patrol - Sovereign BC");
        aliases.put("general patrol - tradition b","General Patrol - Tradition BC");
        aliases.put("general patrol - village hig","General Patrol - Village High");
        aliases.put("piccollo bc","Piccolo BC");
    }
    private RecordedTitleAliases() {}
    public static String expand(String text) {
        String found=aliases.get(ExactPhrasePack.normalize(text));
        return found==null?text:found;
    }
}
''')
p=java/'ExactPhraseResolver.java'; s=p.read_text()
s=rep(s,'            List<File> composed=compose(map,part);','''            String expanded=RecordedTitleAliases.expand(part);
            if(!expanded.equals(part)) {
                File corrected=map.get(ExactPhrasePack.normalize(expanded));
                if(corrected!=null){result.add(corrected);continue;}
                part=expanded;
            }
            List<File> composed=compose(map,part);''')
p.write_text(s)

p=java/'VoiceManager.java'; s=p.read_text()
a=s.index('    public String addOnScript(List<String> phrases){'); b=s.index('    public int installedPhraseCount()',a)
s=s[:a]+'''    public String addOnScript(List<String> phrases){
        if(phrases==null || phrases.isEmpty())throw new IllegalArgumentException("Enter at least one phrase first.");
        return VoiceScriptText.format(phrases);
    }

'''+s[b:]
# Creating a voice must not replace a script the user just loaded or copied.
s=rep(s,'if(ExactPhrasePack.installed(context,source.id))\n                saveCompleteTemplate','if(savedCompleteTemplate().isEmpty() && ExactPhrasePack.installed(context,source.id))\n                saveCompleteTemplate')
anchor='    public int completeTemplateCount(){return completeTemplatePhrases().size();}'
methods=r'''    /** Loading a text manifest changes no audio, speech controls, or checkpoint data. */
    public int setCompleteScript(String raw) throws IOException {
        List<String> phrases=VoiceScriptText.parse(raw);
        saveCompleteTemplate(phrases,"Loaded text script");
        return phrases.size();
    }

    public void importScriptTemplate(Uri uri,ImportCallback callback) {
        if(uri==null){deliver(callback,false,"Choose the script text file.");return;}
        files.execute(()->{
            try(InputStream in=context.getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IOException("Could not open this text file.");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1) {
                    if(out.size()+n>524288)throw new IOException("Choose a script text file smaller than 512 KB.");
                    out.write(buf,0,n);
                }
                int count=setCompleteScript(new String(out.toByteArray(),StandardCharsets.UTF_8));
                deliver(callback,true,"Loaded "+count+" phrases in order. Generate this exact script, then use Import complete-library WAV.");
            }catch(Exception e){deliver(callback,false,safe(e));}
        });
    }

    public String loadedScriptText(){return VoiceScriptText.format(completeTemplatePhrases());}

    public int completeTemplateCount(){return completeTemplatePhrases().size();}'''
s=rep(s,anchor,methods); p.write_text(s)

p=java/'MainActivity.java'; s=p.read_text()
s=rep(s,'    private static final int REQ_COMPLETE_LIBRARY = 4104;','    private static final int REQ_COMPLETE_LIBRARY = 4104;\n    private static final int REQ_SCRIPT_TEXT = 4105;')
anchor='''        addCard(form,button("Import complete-library WAV",false,()->{'''
new=r'''        addCard(form,button("Load script text file (.txt)",true,()->{
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

        addCard(form,button("Import complete-library WAV",false,()->{'''
s=rep(s,anchor,new)
anchor='''        if(requestCode==REQ_COMPLETE_LIBRARY&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){'''
new=r'''        if(requestCode==REQ_SCRIPT_TEXT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            engine.voices.importScriptTemplate(data.getData(),(ok,message)->{
                toast(message);
                if(!isFinishing())new AlertDialog.Builder(this)
                    .setTitle(ok?"Recording script loaded":"Script not loaded")
                    .setMessage(message)
                    .setPositiveButton("OK",(d,w)->{if(ok)voiceLibrary();}).show();
            });
            return;
        }
        if(requestCode==REQ_COMPLETE_LIBRARY&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){'''
s=rep(s,anchor,new)
p.write_text(s)

shutil.copyfile(payload/'EditableMasterScriptTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/EditableMasterScriptTest.java')
assert 'versionCode 135' in (root/'app/build.gradle').read_text()
assert 'Load script text file (.txt)' in (java/'MainActivity.java').read_text()
assert 'addition?250:2000' in (java/'ExactPhrasePack.java').read_text()
assert 'savedCompleteTemplate().isEmpty() &&' in (java/'VoiceManager.java').read_text()
