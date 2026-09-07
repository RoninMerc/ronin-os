from pathlib import Path
root=Path(__file__).resolve().parents[1]/'personal'
j=root/'app/src/main/java/com/ronin/vanta'
def patch(path,old,new):
 s=path.read_text()
 if s.count(old)!=1:raise SystemExit(f'{path.name}: expected one UI review target: {old[:100]} ({s.count(old)})')
 path.write_text(s.replace(old,new,1))
main=j/'MainActivity.java'
patch(root/'app/build.gradle',"    implementation 'androidx.recyclerview:recyclerview:1.4.0'", "    implementation 'androidx.activity:activity:1.13.0'\n    implementation 'androidx.recyclerview:recyclerview:1.4.0'")
patch(main,'public class MainActivity extends Activity {', 'public class MainActivity extends androidx.activity.ComponentActivity {\n  private androidx.activity.OnBackPressedCallback backCallback;')
patch(main,'    shell();','''    backCallback=new androidx.activity.OnBackPressedCallback(false){
      @Override public void handleOnBackPressed(){navigateBack();}
    };
    getOnBackPressedDispatcher().addCallback(this,backCallback);
    shell();''')
patch(main,'    if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->navigateBack());','')
patch(main,'stopConversation();mode=next;', 'stopConversation();mode=next;backCallback.setEnabled(!mode.equals("chat"));')
patch(main,'  @Override public void onBackPressed(){navigateBack();}','')
patch(main,'if (micButton != null) micButton.setText("Listening · tap to finish");','if (micButton != null) {micButton.setText("Finish");micButton.setContentDescription("Finish listening");}\n    status.setText("Connecting microphone…");')
patch(main,'                    : "Speech service error " + code + ". You can type instead.");','''                    : code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Microphone access was denied. Enable it in Android permissions, or type instead."
                    : code==SpeechRecognizer.ERROR_NETWORK || code==SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "Speech recognition could not connect. Check your connection and tap Talk again."
                    : code==SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? "The speech recognizer is busy. Wait briefly, then tap Talk again."
                    : code==SpeechRecognizer.ERROR_AUDIO ? "The microphone could not be opened. Check your audio input or type instead."
                    : "Speech recognition stopped. Tap Talk to retry or type your message.");''')
a=j/'ConversationAdapter.java'
patch(a,'final String text,language,heading,source;final boolean code,user;final int index;', 'final String text,language,heading,source;final boolean code,user;final int index;CharSequence rendered;')
patch(a,'h.content.setText(tail?r.text:styled(r.text,r.code));','if(!tail&&r.rendered==null)r.rendered=styled(r.text,r.code);h.content.setText(tail?r.text:r.rendered);h.content.setMovementMethod(tail||r.code?android.text.method.ArrowKeyMovementMethod.getInstance():android.text.method.LinkMovementMethod.getInstance());')
s=a.read_text();start=s.index('  static CharSequence styled(')
s=s[:start]+'''  static CharSequence styled(String raw,boolean code){return NativeMarkup.render(raw,code);}
}
''';a.write_text(s)
(j/'NativeMarkup.java').write_text(r'''package com.ronin.vanta;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import java.util.regex.*;
/** Native, non-HTML transcript formatting. Source remains unchanged for copy/export. */
public final class NativeMarkup {
 private static final Pattern CODE=Pattern.compile("\\b(class|public|private|static|void|return|if|else|for|while|const|let|function|import|from|def|async|await|new|true|false|null)\\b|\"[^\"\\n]*\"");
 private static final Pattern INLINE=Pattern.compile("\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|\\[([^\\]\\n]+)\\]\\((https?://[^\\s)]+)\\)");
 private static final Pattern HEADING=Pattern.compile("^(#{1,3}) (.+)$");
 private NativeMarkup(){}
 public static CharSequence render(String raw,boolean code){
  if(code){SpannableString s=new SpannableString(raw);Matcher m=CODE.matcher(raw);while(m.find())s.setSpan(new ForegroundColorSpan(raw.charAt(m.start())=='"'?0xffb9c3b0:VantaDesign.GOLD),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return s;}
  SpannableStringBuilder result=new SpannableStringBuilder();String[] lines=raw.split("\n",-1);
  for(int i=0;i<lines.length;i++){
   if(i>0)result.append('\n');String line=lines[i];Matcher heading=HEADING.matcher(line);int start=result.length(),level=0;
   if(heading.matches()){level=heading.group(1).length();line=heading.group(2);}
   else if(line.startsWith("- ")||line.startsWith("* "))line="•  "+line.substring(2);
   boolean quote=line.startsWith("> ");if(quote)line=line.substring(2);
   inline(result,line);
   if(result.length()>start){if(level>0){result.setSpan(new StyleSpan(Typeface.BOLD),start,result.length(),33);result.setSpan(new RelativeSizeSpan(level==1?1.22f:1.1f),start,result.length(),33);}if(quote)result.setSpan(new QuoteSpan(VantaDesign.BORDER,3,12),start,result.length(),33);}
  }
  return result;
 }
 private static void inline(SpannableStringBuilder out,String line){
  Matcher m=INLINE.matcher(line);int at=0;
  while(m.find()){
   out.append(line,at,m.start());int start=out.length();
   if(m.group(1)!=null){out.append(m.group(1));out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),33);}
   else if(m.group(2)!=null){out.append(m.group(2));out.setSpan(new TypefaceSpan("monospace"),start,out.length(),33);out.setSpan(new BackgroundColorSpan(VantaDesign.SURFACE),start,out.length(),33);}
   else if(m.group(3)!=null){out.append(m.group(3));out.setSpan(new StyleSpan(Typeface.ITALIC),start,out.length(),33);}
   else {out.append(m.group(4));out.setSpan(new URLSpan(m.group(5)),start,out.length(),33);}
   at=m.end();
  }
  out.append(line,at,line.length());
 }
}
''')
(root/'app/src/androidTest/java/com/ronin/vanta/NavigationAndFormattingTest.java').write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import android.text.Spanned;
import android.text.style.StyleSpan;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public class NavigationAndFormattingTest {
 @Test public void backDispatcherReturnsToConversationAndReleasesRoot()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   s.onActivity(a->{a.showMode("setup");assertTrue(a.getOnBackPressedDispatcher().hasEnabledCallbacks());a.getOnBackPressedDispatcher().onBackPressed();try{Field f=MainActivity.class.getDeclaredField("mode");f.setAccessible(true);assertEquals("chat",f.get(a));}catch(Exception e){throw new AssertionError(e);}assertFalse(a.getOnBackPressedDispatcher().hasEnabledCallbacks());});
  }
 }
 @Test public void markdownRendersNativelyWithoutChangingCode(){
  CharSequence value=NativeMarkup.render("# Heading\\n**Bold** and `value`\\n- Item\\n[Source](https://example.com)",false);
  assertEquals("Heading\\nBold and value\\n•  Item\\nSource",value.toString());assertTrue(((Spanned)value).getSpans(0,value.length(),StyleSpan.class).length>=2);
  assertEquals("return \\"x\\";", NativeMarkup.render("return \\"x\\";",true).toString());
 }
}
''')
print('Applied lifecycle back navigation, native markup caching and useful voice errors.')
