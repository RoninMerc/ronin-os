from pathlib import Path
import os,json
root=Path(os.environ.get('FIELD_PROJECT','vanta/field-report'))
j=root/'app/src/main/java/com/ronin/fieldreport'
p=j/'MainActivity.java';s=p.read_text().replace('setOrientation(1)','setOrientation(LinearLayout.VERTICAL)').replace('setOrientation(0)','setOrientation(LinearLayout.HORIZONTAL)').replace('Typeface.DEFAULT,1)','Typeface.DEFAULT,android.graphics.Typeface.BOLD)')
s=s.replace('if(result!=RESULT_OK)return;', 'if(result!=RESULT_OK){if(req==PHOTO&&cameraFile!=null){cameraFile.delete();cameraFile=null;}return;}')
s=s.replace('else store.importUri(current,uri);', '''else {store.importUri(current,uri);if(cameraFile!=null&&ReportExport.uri(this,cameraFile).equals(uri)){cameraFile.delete();cameraFile=null;}}''')
p.write_text(s)
p=root/'app/src/main/AndroidManifest.xml';s=p.read_text().replace('android:allowBackup="false"','android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction"');p.write_text(s)
(root/'app/src/main/res/xml/data_extraction.xml').write_text('''<data-extraction-rules><cloud-backup><exclude domain="root" path="."/><exclude domain="file" path="."/><exclude domain="database" path="."/><exclude domain="sharedpref" path="."/><exclude domain="external" path="."/></cloud-backup><device-transfer><exclude domain="root" path="."/><exclude domain="file" path="."/><exclude domain="database" path="."/><exclude domain="sharedpref" path="."/><exclude domain="external" path="."/></device-transfer></data-extraction-rules>''')
p=j/'ReportExport.java';s=p.read_text();old='if(b==null)throw new IOException("A photo could not be rendered.");try{';assert old in s
s=s.replace(old,'if(b==null)throw new IOException("A photo could not be rendered.");b=orient(bytes,b);try{')
mark=' public static byte[] pdf(';assert mark in s
s=s.replace(mark,''' static Bitmap orient(byte[] bytes,Bitmap source)throws IOException {
  int orientation;
  try {orientation=new android.media.ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}
  catch(IOException unsupportedMetadata){return source;}
  Matrix transform=new Matrix();
  switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setScale(1,-1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;case 7:transform.setRotate(270);transform.postScale(-1,1);break;case 8:transform.setRotate(270);break;default:return source;}
  Bitmap rotated=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),transform,true);
  if(rotated!=source)source.recycle();return rotated;
 }
''' +mark)
p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/fieldreport/ReportDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void cameraExifIsRespectedWithoutChangingOriginal()throws Exception {
  Bitmap source=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);source.eraseColor(Color.GRAY);
  File jpeg=new File(context.getCacheDir(),"qa-orientation.jpg");try(OutputStream out=new FileOutputStream(jpeg)){source.compress(Bitmap.CompressFormat.JPEG,90,out);}
  android.media.ExifInterface exif=new android.media.ExifInterface(jpeg.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
  byte[] original=read(jpeg);Bitmap rotated=ReportExport.orient(original,source);assertEquals(90,rotated.getWidth());assertEquals(160,rotated.getHeight());rotated.recycle();
  FieldReport report=report();JSONObject file=store.attach(report,"camera.jpg","image/jpeg",original);assertArrayEquals(original,store.attachment(file));jpeg.delete();
 }
 @Test public void realVoiceMemoRecordsAndBecomesAnEncryptedAttachment()throws Exception {
  InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);
  FieldReport r=report();
  try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
   ready(activity);activity.onActivity(a->{a.display(r);a.toggleRecording();assertTrue(a.status.getText().toString(),a.recording);});
   Thread.sleep(1600);activity.onActivity(MainActivity::stopMemo);
   long deadline=SystemClock.elapsedRealtime()+10000;boolean[] saved={false};
   while(SystemClock.elapsedRealtime()<deadline){activity.onActivity(a->saved[0]=!a.busy&&a.report.attachments.length()==1);if(saved[0])break;Thread.sleep(80);}
   assertTrue("Encoded voice memo saved privately",saved[0]);FieldReport loaded=store.load(r.id);JSONObject metadata=loaded.attachments.getJSONObject(0);assertEquals("audio/mp4",metadata.getString("mime"));
   byte[] encoded=store.attachment(metadata);assertTrue(encoded.length>100);assertEquals("ftyp",new String(encoded,4,4,StandardCharsets.US_ASCII));
   File audio=new File(context.getCacheDir(),"qa-recorded.m4a");ReportExport.write(audio,encoded);
   android.media.MediaMetadataRetriever reader=new android.media.MediaMetadataRetriever();try{reader.setDataSource(audio.getAbsolutePath());assertTrue(Long.parseLong(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))>500);}finally{reader.release();audio.delete();}
  }
 }
}
''';p.write_text(s)
client=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
asset=client/'app/src/main/assets/forge-examples/field-report.json'
if asset.exists():
 project=json.loads(asset.read_text());project['files']=[{'path':str(p.relative_to(root)),'content':p.read_text()} for p in sorted(root.rglob('*')) if p.is_file() and 'build' not in p.parts and '.gradle' not in p.parts]
 asset.write_text(json.dumps(project,separators=(',',':')))
print('Corrected actual lint errors; orientation, backup exclusions, camera cleanup and real audio-recording acceptance.')
