from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
field=Path(os.environ.get('FIELD_PROJECT','vanta/field-report'))
p=field/'app/src/androidTest/java/com/ronin/fieldreport/ReportDeviceTest.java';s=p.read_text();old='void screenshot(String name)throws Exception{Bitmap b=';assert old in s
s=s.replace(old,'void screenshot(String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(350);Bitmap b=')
s=s.rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void anotherApplicationReadsTheEmailDraftAndEveryGrantedAttachment()throws Exception {
  FieldReport r=report();store.attach(r,"photo.png","image/png",photo());store.attach(r,"instructions.txt","text/plain","Job instructions".getBytes(StandardCharsets.UTF_8));
  ReportExport.Package output=ReportExport.prepare(context,r,store);
  String receiver=InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
  Intent email=ReportExport.email(r,output).setClassName(receiver,"com.ronin.fieldreport.TestMailReceiver").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  context.startActivity(email);android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();long end=SystemClock.elapsedRealtime()+10000;boolean observed=false;
  do {Thread.sleep(100);android.view.accessibility.AccessibilityNodeInfo tree=ui.getRootInActiveWindow();if(tree!=null&&!tree.findAccessibilityNodeInfosByText("Received 3 readable attachments for qa@example.invalid").isEmpty()){observed=true;break;}}while(SystemClock.elapsedRealtime()<end);
  assertTrue("A separate test mail-app UID can read the real PDF and originals using temporary grants",observed);screenshot("field-email-handoff.png");
  ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
 }
}
''';p.write_text(s)
p=field/'app/src/androidTest/AndroidManifest.xml';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="com.ronin.fieldreport.TestMailReceiver" android:exported="true" android:theme="@android:style/Theme.Material.NoActionBar"/></application></manifest>''')
p=field/'app/src/androidTest/java/com/ronin/fieldreport/TestMailReceiver.java'
p.write_text('''package com.ronin.fieldreport;
import android.app.Activity;import android.os.Bundle;import android.content.Intent;import android.net.Uri;import android.widget.TextView;import java.io.InputStream;import java.util.ArrayList;
/** Instrumentation-only independent mail-app UID; deliberately never sends an external email. */
public final class TestMailReceiver extends Activity {
 @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);view.setPadding(28,60,28,28);view.setTextSize(18);setContentView(view);
 try{
  Intent in=getIntent();ArrayList<Uri> files=in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);String[] emails=in.getStringArrayExtra(Intent.EXTRA_EMAIL);
  if(files==null||files.size()!=3||emails==null||emails.length!=1||!"qa@example.invalid".equals(emails[0]))throw new Exception("Email fields or attachments differ");
  for(Uri file:files){if(!"content".equals(file.getScheme()))throw new Exception("Not a private content URI");try(InputStream stream=getContentResolver().openInputStream(file)){if(stream==null||stream.read()<0)throw new Exception("Attachment unreadable");}}
  if(!in.getStringExtra(Intent.EXTRA_TEXT).contains("No phone contact"))throw new Exception("Report text was not passed");
  view.setText("Received 3 readable attachments for qa@example.invalid\\n\\nField report email draft\\nPDF + original photo + original document\\n\\nNo external email was sent by this test.");
 }catch(Exception failure){view.setText("Email handoff failed: "+failure.getMessage());}
 }
}''')
print('Mail attachment grants tested across application UIDs; screenshots await a committed UI frame.')
p=field/'app/build.gradle';s=p.read_text();s=s.replace("applicationId 'com.ronin.fieldreport';", "applicationId (project.findProperty('fieldApplicationId') ?: 'com.ronin.fieldreport'); manifestPlaceholders = [fieldLabel:(project.findProperty('fieldApplicationId') ? 'Field Report Test' : 'Ronin Field Report')];")
p.write_text(s)
p=field/'app/src/main/AndroidManifest.xml';p.write_text(p.read_text().replace('android:label="@string/app_name"','android:label="${fieldLabel}"'))
p=field/'README.md';p.write_text(p.read_text()+'\n\nThe separately distributed Field Report Test APK uses com.ronin.fieldreport.demo. The editable Forge example uses com.ronin.fieldreport and is signed by Vanta on the device. Their storage and update identities are intentionally separate. To build the demonstration track, pass -PfieldApplicationId=com.ronin.fieldreport.demo to Gradle.\n')
p=root/'app/src/main/java/com/ronin/vanta/VantaHub.java';s=p.read_text();old='''          if (screen.equals("forge")) {
            forgeRoute = "Select model";''';assert old in s
s=s.replace(old,'''          if (screen.equals("forge")) {
            if(lowRefusalProfile!=null)lowRefusalProfile.setChecked(false);
            manualTarget=c;
            forgeRoute = "Select model";''');p.write_text(s)
print('Demonstration identity is separate from device-signed generated APKs; manual selection keeps the profile indicator accurate.')
