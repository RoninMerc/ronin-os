from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'AndroidBuildFoundation.java';s=p.read_text();old='      while (m.find()) {\n        boolean release =';assert s.count(old)==1
s=s.replace(old,'      while (m.find()) {\n        if (!repairableTaskStatement(sourceText,m.start())) continue;\n        boolean release =',1)
old='  static void put(Map<String, JSONObject> files, String path, String text) throws Exception {';assert s.count(old)==1
s=s.replace(old,r'''  /** Conservative lexical guard: never turn documentation, strings or nested task expressions into code. */
  static boolean repairableTaskStatement(String source,int offset) {
    int line=source.lastIndexOf('\n',Math.max(0,offset-1))+1;
    for(int n=line;n<offset;n++)if(!Character.isWhitespace(source.charAt(n))&&source.charAt(n)!='\uFEFF')return false;
    int braces=0,parens=0,brackets=0,i=0;
    while(i<offset) {
      char ch=source.charAt(i);
      if(source.startsWith("//",i)) {
        int end=source.indexOf('\n',i+2);if(end<0||end>=offset)return false;i=end+1;continue;
      }
      if(source.startsWith("/*",i)) {
        int comments=1;i+=2;
        while(i<offset&&comments>0) {
          if(source.startsWith("/*",i)){comments++;i+=2;}
          else if(source.startsWith("*/",i)){comments--;i+=2;}
          else i++;
        }
        if(comments!=0)return false;continue;
      }
      if(ch=='\''||ch=='"') {
        boolean triple=i+2<source.length()&&source.charAt(i+1)==ch&&source.charAt(i+2)==ch;
        int width=triple?3:1;i+=width;boolean closed=false;
        while(i<offset) {
          if(!triple&&source.charAt(i)=='\\'){i+=2;continue;}
          if(source.charAt(i)==ch&&(!triple||(i+2<offset&&source.charAt(i+1)==ch&&source.charAt(i+2)==ch))) {
            i+=width;closed=true;break;
          }
          i++;
        }
        if(!closed)return false;continue;
      }
      // Slashy/dollar-slashy Groovy literals are deliberately not rewritten. Neither
      // is an ambiguous expression containing division: the model can inspect it.
      if(ch=='/')return false;
      if(ch=='{')braces++;else if(ch=='}')braces--;
      if(ch=='(')parens++;else if(ch==')')parens--;
      if(ch=='[')brackets++;else if(ch==']')brackets--;
      if(braces<0||parens<0||brackets<0)return false;
      i++;
    }
    return braces==0&&parens==0&&brackets==0;
  }

''' + old,1);p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/BuildFoundationTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+r'''
  private static final String DOCUMENTED_GATE="tasks.named('assembleRelease') { dependsOn('testReleaseUnitTest') }";
  private static String normalizeScript(String script)throws Exception {
    JSONObject project=project();put(project,"app/build.gradle",script);
    return get(AndroidBuildFoundation.prepare(project).project,"app/build.gradle");
  }
  @Test public void lineCommentIsNeverExpandedIntoExecutableCode()throws Exception {
    String script="plugins { id 'com.android.application' }\n// "+DOCUMENTED_GATE+"\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void multilineCommentIsNotRewritten()throws Exception {
    String script="/* Example only\n"+DOCUMENTED_GATE+"\n*/\nplugins { id 'com.android.application' }";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void kotlinNestedCommentIsNotRewritten()throws Exception {
    String script="/* outside /* nested */\n"+DOCUMENTED_GATE+"\n*/";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void groovyTripleQuotedExampleIsNotRewritten()throws Exception {
    String mark="'"; String triple=mark+mark+mark; String script="def example = "+triple+"\n"+DOCUMENTED_GATE+"\n"+triple+"\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void kotlinRawStringExampleIsNotRewritten()throws Exception {
    String script="val example = \"\"\"\n"+DOCUMENTED_GATE+"\n\"\"\"\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void existingAfterEvaluateLogicIsNotChanged()throws Exception {
    String script="afterEvaluate {\n"+DOCUMENTED_GATE+"\n}\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void taskExpressionUsedAsArgumentIsNotChanged()throws Exception {
    String script="consume(\n"+DOCUMENTED_GATE+"\n)\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void mixedDocumentationAndRealTopLevelGateChangesOnlyTheRealCode()throws Exception {
    String comment="// "+DOCUMENTED_GATE+"\n";
    String script=comment+DOCUMENTED_GATE+"\n";
    String normalized=normalizeScript(script);
    assertTrue(normalized.startsWith(comment));assertTrue(normalized.contains("tasks.configureEach"));
    assertFalse(normalized.substring(comment.length()).contains("tasks.named("));
  }
  @Test public void ambiguousGroovySlashyInputIsConservativelyPreserved()throws Exception {
    String script="def example = /\n"+DOCUMENTED_GATE+"\n/\n";
    assertEquals(script,normalizeScript(script));
  }
  @Test public void ordinaryStringsDoNotPreventSafeTopLevelRepair()throws Exception {
    String prefix="def site = 'https://example.invalid/{documentation}'\n";
    String changed=normalizeScript(prefix+DOCUMENTED_GATE+"\n");
    assertTrue(changed.startsWith(prefix));assertTrue(changed.contains("tasks.configureEach"));
  }
}
''';p.write_text(s)
print('Automatic build edits exclude comments, quoted examples and nested expressions; ten preservation regressions added.')
