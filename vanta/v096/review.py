"""Finish 0.9.6 against the revised label-discovery contract without disabling test gates."""
from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
main=root/'app/src/main/java/com/ronin/vanta'
tests=root/'app/src/test/java/com/ronin/vanta'
def replace(path,old,new):
 text=path.read_text()
 if text.count(old)!=1:raise AssertionError(f'{path}: expected one occurrence, found {text.count(old)}: {old[:100]}')
 path.write_text(text.replace(old,new,1))
# Revise the intentionally changed eligibility contract, preserving the separate metadata assertion.
p=tests/'AutomaticForgeRecoveryTest.java';s=p.read_text()
start=s.index('  public void nameAloneCannotSatisfyLowRefusal()');end=s.index('\n  @Test',start)
old=s[start:end]
new=old.replace('nameAloneCannotSatisfyLowRefusal','providerNameLabelCanQualifyWithoutBecomingVerifiedMetadata')
new=new.replace('    assertFalse(\n        ForgeRecovery.eligible(','    assertTrue(\n        ForgeRecovery.eligible(',1)
new=new.replace('    assertTrue(','    assertFalse(ModelFacts.lowRefusalVerified(m));\n    assertEquals("Low-refusal · provider model-name label", ModelFacts.lowRefusalEvidence(m));\n    assertTrue(',1)
assert new!=old
replace(p,old,new)
# The chosen model and verified-metadata assertions already passed. The remaining old
# assertion was case-sensitive against the capitalized user-facing label.
replace(tests/'MasterRebuildTest.java','assertTrue(PromptStrategy.forgeRestrictionLabel(shadow).contains("low-refusal"));','assertEquals("Low-refusal · provider/curated metadata", PromptStrategy.forgeRestrictionLabel(shadow));')
replace(main/'ModelFacts.java','  public static boolean lowRefusalVerified(ModelInfo m) {\n    Set<String> values','  public static boolean lowRefusalVerified(ModelInfo m) {\n    if (m == null) return false;\n    Set<String> values')
# Compatibility must not discard the caller's bound or misrepresent upstream billing.
replace(main/'ApiClient.java','      minimal.remove("max_tokens");','      // Keep the output limit: compatibility must not create an unbounded request.')
replace(main/'ApiClient.java','// available. HTTP 400/422 means the request was rejected before inference, so one minimal\n      // compatibility retry does not replay accepted paid work.','// available. Only explicit HTTP validation responses enter this single compatibility\n      // attempt; uncertain streams do not. Provider billing for rejected requests is not assumed.')
replace(main/'ApiClient.java','|| first.getMessage().toLowerCase(Locale.ROOT).contains("content policy")) throw first;','|| first.getMessage().toLowerCase(Locale.ROOT).matches(\n              "(?s).*(content policy|safety|moderation|billing|quota|credit|payment|balance|invalid api|incorrect api|unauthorized|authentication|context length|context window|maximum context|unsupported modality).*")) throw first;')
p=tests/'Vanta096RoutingTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+r'''
  @Test public void missingModelDoesNotCrashTheLowRefusalFilter() {
    assertFalse(ModelFacts.lowRefusalEligible(null));assertFalse(ModelFacts.lowRefusalVerified(null));
  }
  @Test public void exportedChatTextDoesNotRequireVisionOrACodingBadge() throws Exception {
    ModelInfo m=ApiClient.parseModel(featherless(),new JSONObject().put("id","community/Useful-32B-abliterated").put("model_class","qwen").put("context_length",32768));
    assertTrue(ModelFacts.lowRefusalEligible(m));assertFalse(ModelFacts.lowRefusalVerified(m));assertFalse(m.codeCapable);
    VantaRouter.Candidate only=new VantaRouter.Candidate(featherless(),m,0);
    assertSame(only,VantaRouter.route("chat","Extract and correct the code from this exported conversation",true,false,Collections.singletonList(only),"").candidate);
    assertSame(only,ForgeRouting.select(Collections.singletonList(only),"Fix the attached project","Low-refusal",""));
  }
  @Test public void metadataEvidenceWinsWhenNameAndMetadataBothExist() throws Exception {
    ModelInfo m=candidate("community/model-heretic").model;
    m.spec.put("provider_record",new JSONObject().put("tags",new JSONArray().put("abliterated")));
    assertTrue(ModelFacts.lowRefusalNameLabelled(m));assertTrue(ModelFacts.lowRefusalVerified(m));
    assertEquals("Low-refusal · provider/curated metadata",ModelFacts.lowRefusalEvidence(m));
  }
  @Test public void cachedModelRetainsLabelsWithoutPromotingThemToProviderFacts() throws Exception {
    ModelInfo source=candidate("community/Model-OBLITERATED").model;
    ModelInfo restored=ModelInfo.fromJson(new JSONObject(source.toJson().toString()));
    assertTrue(ModelFacts.lowRefusalEligible(restored));assertFalse(ModelFacts.lowRefusalVerified(restored));
    assertFalse(ModelFacts.facet(restored,"Alignment").contains("obliterated"));
  }
  @Test public void disabledAndNonChatModelsRemainIneligibleForAutomaticCoding() {
    VantaRouter.Candidate disabled=candidate("community/coder-heretic");disabled.model.enabled=false;
    VantaRouter.Candidate audio=candidate("community/voice-uncensored");audio.model.type="tts";
    assertNull(ForgeRouting.select(Arrays.asList(disabled,audio),"Fix this source","Low-refusal",""));
  }
  @Test public void aDescriptionDoesNotChangeAnUnlabelledModelsAlignment() {
    ModelInfo m=new ModelInfo("community/standard","Standard","text","Compared with uncensored and heretic models");
    assertFalse(ModelFacts.lowRefusalEligible(m));assertFalse(ModelFacts.lowRefusalVerified(m));
  }
  @Test public void oneFeatherlessProviderIsEnoughForOrdinaryLowRefusalTextChat() throws Exception {
    List<VantaRouter.Candidate> available=Arrays.asList(candidate("community/standard"),candidate("community/assistant-low_refusal"));
    List<VantaRouter.Candidate> filtered=new ArrayList<>();
    for(VantaRouter.Candidate c:available)if(ModelFacts.lowRefusalEligible(c.model))filtered.add(c);
    assertEquals(1,filtered.size());assertSame(filtered.get(0),VantaRouter.route("chat","Help organise my report",false,false,filtered,"").candidate);
  }
}
''';p.write_text(s)
(tests/'FeatherlessCompatibilityTest.java').write_text(r'''package com.ronin.vanta;
import static org.junit.Assert.*;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;import org.junit.Test;
/** Isolated recorded-wire checks. No paid provider calls. */
public class FeatherlessCompatibilityTest {
  static final class Reply extends HttpURLConnection {
    final int code;final String type,text;final ByteArrayOutputStream request=new ByteArrayOutputStream();
    Reply(int code,String type,String text)throws Exception{super(new URL("https://api.featherless.ai/v1/chat/completions"));this.code=code;this.type=type;this.text=text;}
    public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
    public OutputStream getOutputStream(){return request;}public int getResponseCode(){return code;}
    public String getContentType(){return type;}public String getHeaderField(String key){return null;}
    public InputStream getInputStream(){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
    public InputStream getErrorStream(){return getInputStream();}
    JSONObject sent()throws Exception{return new JSONObject(request.toString("UTF-8"));}
  }
  static Reply error(int status,String message)throws Exception{return new Reply(status,"application/json",new JSONObject().put("error",new JSONObject().put("message",message)).toString());}
  static Reply ok()throws Exception{return new Reply(200,"application/json","{\"choices\":[{\"message\":{\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}");}
  String execute(Net.Call call)throws Exception{
    ProviderConfig provider=new ProviderConfig("featherless","Featherless","featherless","https://api.featherless.ai/v1");
    JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","Correct my project").put("attachments",new JSONArray().put(new JSONObject().put("kind","text").put("name","venice-export.md").put("text","public class NeutralReport {}"))));
    return ApiClient.chat(provider,"fixture-key","community/assistant-abliterated",history,"Preserve the source",3000,call,x->{});
  }
  @Test public void validationRetryKeepsModelAttachmentsAndOutputBound()throws Exception{
    for(int status:new int[]{400,422}){
      Reply first=error(status,"Unsupported stream parameter"),second=ok();List<Reply> seen=new ArrayList<>();Net.Call call=new Net.Call();
      call.connectionFactory=url->{Reply next=seen.isEmpty()?first:second;seen.add(next);return next;};
      assertEquals("ready",execute(call));assertEquals(2,seen.size());assertTrue(first.sent().getBoolean("stream"));assertFalse(second.sent().getBoolean("stream"));assertEquals(3000,second.sent().getInt("max_tokens"));
      assertEquals(first.sent().getString("model"),second.sent().getString("model"));assertEquals(first.sent().getJSONArray("messages").toString(),second.sent().getJSONArray("messages").toString());assertTrue(second.sent().getJSONArray("messages").toString().contains("NeutralReport"));
    }
  }
  @Test public void authenticationBillingAvailabilityAndPolicyNeverCauseCompatibilityReplay()throws Exception{
    for(String message:new String[]{"Insufficient credit balance","Model is not available for inference","Request violates content policy","Invalid API key","Maximum context length exceeded"}){
      Reply first=error(400,message);List<Reply> seen=new ArrayList<>();Net.Call call=new Net.Call();call.connectionFactory=url->{seen.add(first);return first;};
      try{execute(call);fail(message);}catch(Net.HttpError expected){assertEquals(400,expected.status);}assertEquals(message,1,seen.size());
    }
  }
  @Test public void unauthorizedStatusIsNotACompatibilityFailure()throws Exception{
    Reply first=error(401,"Unauthorized");List<Reply> seen=new ArrayList<>();Net.Call call=new Net.Call();call.connectionFactory=url->{seen.add(first);return first;};
    try{execute(call);fail();}catch(Net.HttpError expected){assertEquals(401,expected.status);}assertEquals(1,seen.size());
  }
  @Test public void twoRejectedRequestsStopInsteadOfLooping()throws Exception{
    Reply first=error(400,"Unsupported stream parameter"),second=error(422,"Invalid model parameter");List<Reply> seen=new ArrayList<>();Net.Call call=new Net.Call();
    call.connectionFactory=url->{Reply next=seen.isEmpty()?first:second;seen.add(next);return next;};
    try{execute(call);fail();}catch(Net.HttpError expected){assertEquals(422,expected.status);assertEquals(1,expected.getSuppressed().length);}assertEquals(2,seen.size());
  }
  @Test public void streamedErrorsAreNotMisrepresentedAsUnacceptedHttpRequests()throws Exception{
    Reply first=new Reply(200,"text/event-stream","data: {\"error\":{\"code\":\"bad_request\",\"message\":\"Invalid request\"}}\n\n");List<Reply> seen=new ArrayList<>();Net.Call call=new Net.Call();call.connectionFactory=url->{seen.add(first);return first;};
    try{execute(call);fail();}catch(IOException expected){assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("failed"));}assertEquals(1,seen.size());
  }
  @Test public void uncertainSocketFailureIsNotAutomaticallyReplayed()throws Exception{
    int[] seen={0};Net.Call call=new Net.Call();call.connectionFactory=url->{seen[0]++;throw new SocketException("Socket closed");};
    try{execute(call);fail();}catch(SocketException expected){assertEquals("Socket closed",expected.getMessage());}assertEquals(1,seen[0]);
  }
}
''')
print('Reviewed discovery-vs-metadata assertions, Featherless-only text routing, bounded compatibility and thirteen additional regressions.')
