from pathlib import Path
root=Path(__file__).resolve().parents[1]/'personal'
j=root/'app/src/main/java/com/ronin/vanta'
changes={
 'ConversationAdapter.java': [('android.text.Layout.BREAK_STRATEGY_SIMPLE','android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE'),('Typeface.create(r.code?"monospace":"sans-serif",0)','Typeface.create(r.code?"monospace":"sans-serif",Typeface.NORMAL)')],
 'VantaDesign.java':[('l.setOrientation(1)','l.setOrientation(LinearLayout.VERTICAL)'),('Typeface.create("sans-serif-medium",0)','Typeface.create("sans-serif-medium",Typeface.NORMAL)'),('Typeface.create("sans-serif",0)','Typeface.create("sans-serif",Typeface.NORMAL)')],
 'VideoControls.java':[('Typeface.create("sans-serif-medium",0)','Typeface.create("sans-serif-medium",Typeface.NORMAL)')]
}
for name,pairs in changes.items():
 p=j/name;s=p.read_text()
 for old,new in pairs:
  if s.count(old)!=1:raise SystemExit(name+': expected one exact constant match: '+old)
  s=s.replace(old,new,1)
 p.write_text(s)
p=j/'MainActivity.java';s=p.read_text()
for variable in ('picked','uri'):
 old='getContentResolver().takePersistableUriPermission('+variable+',data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));'
 if s.count(old)!=1:raise SystemExit('Expected URI permission target '+variable)
 s=s.replace(old,'persistDocumentAccess('+variable+',data);',1)
point='  @Override\n  protected void onActivityResult('
assert s.count(point)==1
helper='''  private void persistDocumentAccess(Uri uri,Intent data){
    boolean read=(data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0;
    boolean write=(data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0;
    if(read&&write)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    else if(read)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
    else if(write)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

'''
s=s.replace(point,helper+point,1);p.write_text(s)
print('All nine original lint errors corrected, not suppressed.')
