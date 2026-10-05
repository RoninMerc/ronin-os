from pathlib import Path
import shutil, sys, hashlib
root=Path(sys.argv[1]); here=Path(__file__).parent
base=root/'app/src/main/java/com/ronin/vanta'
def change(name, before, after, count=1):
    p=base/name;s=p.read_text()
    assert s.count(before)==count,(name,s.count(before),before[:80])
    p.write_text(s.replace(before,after),encoding='utf-8')
change('Net.java','    ConnectionFactory connectionFactory;','    ConnectionFactory connectionFactory;\n    final GenerationProgress generation = new GenerationProgress();')
change('Net.java','      return result;\n    }\n\n    Transport transport;', '''      try { result.put("generation", generation.diagnostics()); }
      catch (Exception ignored) { /* Counters cannot invalidate the original transport error. */ }
      return result;
    }

    Transport transport;''')
change('Net.java','Ronin-Vanta/0.17.5','Ronin-Vanta/0.17.6')
change('ChatProtocol.java','    void event(String data, ApiClient.Status callback) throws Exception {','''    void event(String data, Net.Call call, ApiClient.Status callback) throws Exception {
      call.generation.event();''')
change('ChatProtocol.java','      String addition = "";','''      // Report only validated semantic channels, not role, usage, heartbeat, or unknown data.
      if (type.equals("response.reasoning_text.delta") || type.equals("response.reasoning_summary_text.delta"))
        call.generation.text("reasoning", string(event, "delta"));
      String addition = "";''')
change('ChatProtocol.java','''        if (block != null && block.optString("type").equals("text"))
          addition = block.optString("text", "");''','''        if (block != null && block.optString("type").equals("text"))
          addition = block.optString("text", "");
        if (block != null && block.optString("type").equals("thinking"))
          call.generation.text("reasoning", string(block, "thinking"));''')
change('ChatProtocol.java','''        if (delta != null && delta.optString("type").equals("text_delta"))
          addition = delta.optString("text", "");''','''        if (delta != null && delta.optString("type").equals("text_delta"))
          addition = delta.optString("text", "");
        if (delta != null && delta.optString("type").equals("thinking_delta"))
          call.generation.text("reasoning", string(delta, "thinking"));''')
change('ChatProtocol.java','''        JSONObject choice = choices.getJSONObject(0);
        JSONObject delta = choice.optJSONObject("delta");''','''        JSONObject choice = choices.getJSONObject(0);
        if (choice.optInt("index", 0) != 0) return;
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
          // Both names are used by deployed OpenAI-compatible reasoning parsers.
          String reasoning = string(delta, "reasoning_content");
          if (reasoning.isEmpty()) reasoning = string(delta, "reasoning");
          call.generation.text("reasoning", reasoning);
        }''')
change('ChatProtocol.java','append(addition, callback);','append(addition, call, callback);',2)
change('ChatProtocol.java','''    void append(String addition, ApiClient.Status callback) throws Exception {''','''    private static String string(JSONObject value, String key) {
      Object raw = value.opt(key); return raw instanceof String ? (String) raw : "";
    }

    void append(String addition, Net.Call call, ApiClient.Status callback) throws Exception {''')
change('ChatProtocol.java','''      text.append(addition);
      callback.onStatus''','''      text.append(addition);
      // Count raw decoded answer text BEFORE the UI's internal-tool placeholder filter.
      call.generation.text("answer", addition);
      callback.onStatus''')
change('ChatProtocol.java','decoder.event(event.toString(), callback);','decoder.event(event.toString(), call, callback);',2)
change('ApiClient.java','''        delta.onStatus(answer);
        return answer;''','''        call.generation.text("answer", answer);
        delta.onStatus(answer);
        return answer;''')
change('JobOperations.java','    GenerationWatchdog watchdog = forge ? new GenerationWatchdog(c) : null;', '    GenerationWatchdog watchdog = null;')
change('JobOperations.java','''      c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;
      String answer =''','''      c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;
      // Metadata validation has separate transport limits; the generation timer begins here.
      watchdog = forge ? new GenerationWatchdog(c) : null;
      String answer =''')
change('JobOperations.java','                    if (watchdog != null) watchdog.progress(text);\n','')
for name in ['GenerationProgress.java','GenerationWatchdog.java']:
    shutil.copyfile(here/name,base/name)
shutil.copyfile(here/'Generation176RegressionTest.java',root/'app/src/test/java/com/ronin/vanta/Generation176RegressionTest.java')
p=root/'app/build.gradle';s=p.read_text().replace("versionCode 175","versionCode 176").replace("versionName '0.17.5'","versionName '0.17.6'");p.write_text(s)
print('Applied 0.17.6 semantic streaming progress. No model, key, project or stored task migration.')
