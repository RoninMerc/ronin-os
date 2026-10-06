from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/test/java/com/ronin/vanta/Capacity177RegressionTest.java'
s=p.read_text()
a=s.index('  @Test public void whitespaceReasoningDoesNotImplyNoOutput()')
b=s.index('  @Test public void whitespaceAnswerCannotReplay()',a)
s=s[:a]+'''  @Test public void whitespaceReasoningDoesNotImplyNoOutput()throws Exception{
    assertTrue(typed(delta(new JSONObject().put("reasoning_content","  \\n ").toString())+rejected()).outputObserved);
  }
'''+s[b:]
p.write_text(s,encoding='utf-8')
print('Normalised whitespace reasoning fixture; meaningful-activity and replay assertions retained.')
