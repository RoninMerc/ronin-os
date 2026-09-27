from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v128 patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 127','versionCode 128');s=rep(s,"versionName '1.1.17'","versionName '1.1.18'");p.write_text(s)

shutil.copyfile(payload/'VoiceManager.java',java/'VoiceManager.java')

# Allow complete phrase entries, not only very short pronunciation fragments.
p=java/'SpeechPreferences.java';s=p.read_text()
s=s.replace('if(type.equals(PHRASE)&&(source.length()>100||map(type).size()>=150&&!map(type).containsKey(SpeechRules.key(source))))throw new IllegalArgumentException("Use a phrase of up to 100 characters; maximum 150 pronunciation rules.");',
'''if(type.equals(PHRASE)&&(source.length()>500||map(type).size()>=500&&!map(type).containsKey(SpeechRules.key(source))))throw new IllegalArgumentException("Use a phrase of up to 500 characters; maximum 500 phrase rules.");''')
p.write_text(s)

p=java/'MainActivity.java';s=p.read_text()
anchor='''        addCard(form,button("Test "+active.name,true,()->{
            if(!engine.voices.ready())toast("Import Part 1 or Part 2 for "+active.name+" first.");
            else engine.voices.test();
        }));'''
replacement='''        Button phraseList=button("Full phrase & word list",false,this::voicePhraseList);
        phraseList.setTag("voice_phrase_list");
        addCard(form,phraseList);

        addCard(form,button("Test "+active.name,true,()->{
            if(!engine.voices.ready())toast("Import Part 1 or Part 2 for "+active.name+" first.");
            else engine.voices.test();
        }));'''
s=rep(s,anchor,replacement)

insert_at=s.index('    private void addVoiceProfile(){')
methods=r'''    private String phraseCompact(String value){
        String v=value==null?"":value;
        return v.length()<=150?v:v.substring(0,147)+"…";
    }

    private void voicePhraseList(){
        final java.util.List<VoiceManager.PhraseItem> items=engine.voices.phraseItems(engine.recent(),engine.guards());
        LinearLayout form=vertical();form.setPadding(dp(18),dp(8),dp(18),dp(8));
        form.addView(text("FULL PHRASE & WORD LIST",11,ACCENT,true));gap(form,6);
        form.addView(text("This is the complete current phrase catalogue used to build the two voice scripts. Edit any entry to change exactly how it is spoken. For abbreviations such as PL, edit the reusable entry itself (for example, Balmara Pl → Balmara Place). That lets the same corrected wording apply inside patrol alerts and the location field, which also prevents duplicated location readouts.",12,MUTED,false));gap(form,10);
        form.addView(text("After changing wording, Patrol Link refreshes Part 1 and Part 2. The affected half must be regenerated on the voice website and re-imported before the new wording can play in the exact voice.",11,AMBER,false));gap(form,12);

        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search phrases, names, places or incidents");search.setTextColor(WHITE);search.setHintTextColor(MUTED);search.setTag("voice_phrase_search");
        form.addView(search,new LinearLayout.LayoutParams(-1,-2));gap(form,8);
        TextView count=text("",11,ACCENT,true);form.addView(count);gap(form,6);
        LinearLayout list=vertical();form.addView(list);
        final int[] limit={60};
        final AlertDialog[] holder=new AlertDialog[1];
        final Runnable[] render=new Runnable[1];
        render[0]=()->{
            list.removeAllViews();
            String q=SpeechRules.key(search.getText().toString());
            java.util.ArrayList<VoiceManager.PhraseItem> matched=new java.util.ArrayList<>();
            for(VoiceManager.PhraseItem item:items){
                if(q.isEmpty()||SpeechRules.key(item.original+" "+item.spoken).contains(q))matched.add(item);
            }
            count.setText(matched.size()+" matching phrases"+(matched.size()>limit[0]?" · showing first "+limit[0]:""));
            if(matched.isEmpty())list.addView(text("No phrase matches that search.",13,MUTED,false));
            int max=Math.min(limit[0],matched.size());
            for(int i=0;i<max;i++){
                VoiceManager.PhraseItem item=matched.get(i);
                String label=(item.edited?"EDITED · ":"")+"Part "+item.part+"\\nOriginal: "+phraseCompact(item.original)+"\\nSays: "+phraseCompact(item.spoken);
                Button b=button(label,item.edited,()->{
                    if(holder[0]!=null)holder[0].dismiss();
                    voicePhraseEdit(item);
                });
                b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                b.setTag("voice_phrase:"+SpeechRules.key(item.original));
                list.addView(b,new LinearLayout.LayoutParams(-1,-2));
                gap(list,6);
            }
            if(matched.size()>limit[0]){
                Button more=button("Show 60 more",false,()->{limit[0]+=60;render[0].run();});
                more.setTag("voice_phrase_more");list.addView(more);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){limit[0]=60;render[0].run();}
            public void afterTextChanged(android.text.Editable e){}
        });
        render[0].run();

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        holder[0]=new AlertDialog.Builder(this).setTitle("Voice phrase dictionary").setView(scroll).setPositiveButton("Close",null).create();
        holder[0].show();
    }

    private void voicePhraseEdit(VoiceManager.PhraseItem item){
        LinearLayout form=vertical();form.setPadding(dp(20),dp(8),dp(20),dp(8));
        form.addView(text("ORIGINAL",11,MUTED,true));gap(form,4);
        form.addView(text(item.original,16,WHITE,true));gap(form,12);
        form.addView(text("SAY THIS INSTEAD",11,ACCENT,true));gap(form,4);
        EditText spoken=new EditText(this);spoken.setTag("voice_phrase_spoken");spoken.setText(item.edited?item.spoken:item.original);spoken.setTextColor(WHITE);spoken.setHintTextColor(MUTED);spoken.setMinLines(2);spoken.setGravity(Gravity.TOP);spoken.setSelectAllOnFocus(false);
        form.addView(spoken,new LinearLayout.LayoutParams(-1,-2));gap(form,10);
        TextView preview=text("",13,WHITE,false);preview.setTag("voice_phrase_preview");form.addView(preview);gap(form,10);
        Runnable update=()->preview.setText("Will say:\\n"+(spoken.getText().toString().trim().isEmpty()?item.original:spoken.getText().toString().trim()));
        spoken.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){update.run();}
            public void afterTextChanged(android.text.Editable e){}
        });update.run();
        form.addView(text("Tip: for abbreviations or duplicated locations, edit the smallest reusable phrase. Example: Balmara Pl → Balmara Place. Patrol Link applies that wording before checking whether the alert already contains the location.",11,MUTED,false));gap(form,10);

        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit spoken phrase").setView(form)
                .setPositiveButton("Save",null).setNegativeButton("Cancel",(d,w)->voicePhraseList())
                .setNeutralButton("Use original",null).create();
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTag("voice_phrase_save");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
                try{
                    String value=spoken.getText().toString().trim();
                    engine.voices.savePhraseWording(item.original,value);
                    java.util.List<VoiceManager.Part> refreshed=engine.voices.prepareParts(engine.recent(),engine.guards());
                    int part=1;
                    for(VoiceManager.PhraseItem p:engine.voices.phraseItems(engine.recent(),engine.guards())){
                        if(SpeechRules.key(p.original).equals(SpeechRules.key(item.original))){part=p.part;break;}
                    }
                    dialog.dismiss();
                    toast("Saved. Regenerate and import Part "+part+" for "+engine.voices.activeName()+".");
                    voicePhraseList();
                }catch(Exception ex){toast(ex.getMessage()==null?"Could not save that phrase.":ex.getMessage());}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTag("voice_phrase_restore");
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{
                try{
                    engine.voices.savePhraseWording(item.original,"");
                    engine.voices.prepareParts(engine.recent(),engine.guards());
                    dialog.dismiss();toast("Original wording restored.");voicePhraseList();
                }catch(Exception ex){toast(ex.getMessage()==null?"Could not restore that phrase.":ex.getMessage());}
            });
        });
        dialog.show();
    }

'''
s=s[:insert_at]+methods+s[insert_at:]
p.write_text(s)

shutil.copyfile(payload/'PhraseCatalogTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/PhraseCatalogTest.java')

assert 'versionCode 128' in (root/'app/build.gradle').read_text()
assert 'Full phrase & word list' in (java/'MainActivity.java').read_text()
assert 'phraseItems' in (java/'VoiceManager.java').read_text()
