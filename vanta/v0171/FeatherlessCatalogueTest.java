package com.ronin.vanta;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class FeatherlessCatalogueTest {
  static ProviderConfig provider() { return new ProviderConfig("featherless", "Featherless", "featherless", "https://api.featherless.ai/v1"); }
  static List<ModelInfo> parse(String json) throws Exception {
    List<ModelInfo> out = new ArrayList<>();
    FeatherlessCatalogue.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), provider(), out, new HashSet<>(), new Net.Call());
    return out;
  }
  static void rejected(String json) throws Exception {
    try { parse(json); fail("Malformed catalogue was accepted: " + json.substring(0, Math.min(100, json.length()))); }
    catch (IOException | IllegalStateException | JSONException expected) { }
  }
  static class ResponseTransport implements Net.Transport {
    int calls;
    String json;
    Map<String,String> headers;
    ResponseTransport(String data) { json=data; }
    public Net.Response request(String method,String url,Map<String,String> h,byte[] body,String mime,int max,Net.Call call) throws Exception {
      calls++; headers=h; assertEquals("GET",method); assertNull(body);
      assertFalse(url.contains("capabilities=")); assertFalse(url.contains("modalities="));
      return new Net.Response(json.getBytes(StandardCharsets.UTF_8),"application/json");
    }
    public Net.Response download(String u,Map<String,String> h,int m,Net.Call c) { throw new AssertionError("No file download expected"); }
  }
  @Test public void bulkDoesNotForcePageSizeOrPageNumber() {
    String url=CataloguePaging.first(provider());assertFalse(url.contains("page="));assertTrue(url.contains("not_deployed"));
  }
  @Test public void bulkDoesNotExposeSavedKeyOrRequestFacets() throws Exception {
    ResponseTransport transport=new ResponseTransport("{\"data\":[{\"id\":\"a/b\"}],\"total\":99}");
    Net.Call call=new Net.Call();call.transport=transport;
    List<ModelInfo> rows=ApiClient.listModels(provider(),"private-fixture-never-transmitted",call);
    assertEquals(1,transport.calls);assertTrue(transport.headers.isEmpty());assertEquals(1,rows.size());
    assertTrue(rows.get(0).spec.optString("catalogue_warning").contains("99"));
  }
  @Test public void customEndpointRetainsItsOwnAuthentication() throws Exception {
    ProviderConfig p=provider();p.baseUrl="https://example.invalid/v1";
    ResponseTransport t=new ResponseTransport("{\"data\":[{\"id\":\"fixture\"}]}");Net.Call c=new Net.Call();c.transport=t;
    ApiClient.listModels(p,"private-fixture",c);assertEquals("Bearer private-fixture",t.headers.get("Authorization"));
  }
  @Test public void numericTotalAloneDoesNotInventPages() throws Exception {
    assertNull(CataloguePaging.next(provider(),new JSONObject("{\"total\":999999,\"per_page\":1000}"),1000,1));
  }
  @Test public void paginationContainerIsRespected() throws Exception {
    String next=CataloguePaging.next(provider(),new JSONObject("{\"pagination\":{\"current_page\":1,\"total_pages\":2,\"per_page\":50}}"),50,1);
    assertTrue(next.contains("per_page=50"));assertTrue(next.endsWith("page=2"));
  }
  @Test public void numericNextPageIsRespected() throws Exception {
    assertTrue(CataloguePaging.next(provider(),new JSONObject("{\"meta\":{\"next_page\":2}}"),10,1).endsWith("page=2"));
  }
  @Test public void safeExplicitNextUrlIsRespected() throws Exception {
    assertEquals("https://api.featherless.ai/v1/models?cursor=abc",CataloguePaging.next(provider(),new JSONObject("{\"links\":{\"next\":\"/v1/models?cursor=abc\"}}"),10,1));
  }
  @Test public void unrelatedOriginsAndPathsAreRejected() throws Exception {
    for(String next:Arrays.asList("https://other.invalid/v1/models?page=2","https://api.featherless.ai/account","http://api.featherless.ai/v1/models","https://user:pass@api.featherless.ai/v1/models","https://api.featherless.ai/v1/models#fragment")) {
      try {CataloguePaging.next(provider(),new JSONObject().put("next",next),10,1);fail(next);}catch(IOException expected){}
    }
  }
  @Test public void skippedAndRepeatedPagesAreRejected() throws Exception {
    for(int next:new int[]{1,3})try{CataloguePaging.next(provider(),new JSONObject().put("next_page",next),10,1);fail();}catch(IOException expected){}
  }
  @Test public void duplicateIdsAreDeduplicatedWithoutDroppingOtherModels() throws Exception {
    assertEquals(2,parse("{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":\"b\"}]}").size());
  }
  @Test public void metadataIsPreservedForDetailAndRouting() throws Exception {
    ModelInfo m=parse("{\"data\":[{\"id\":\"owner/test\",\"context_length\":131072,\"max_completion_tokens\":16384,\"vision_supported\":true,\"capabilities\":[\"code\"],\"status\":\"not_deployed\",\"is_gated\":true}]}").get(0);
    assertEquals(131072,m.contextTokens);assertTrue(m.supportsVision);assertTrue(m.codeCapable);
    assertEquals("not_deployed",m.spec.getJSONObject("provider_record").getString("status"));
    assertTrue(m.spec.getJSONObject("provider_record").getBoolean("is_gated"));
  }
  @Test public void unicodeAndEscapedCharactersSurviveStreaming() throws Exception {
    assertEquals("é/测试",parse("{\"data\":[{\"id\":\"é/测试\",\"description\":\"brace } and quote \\\"\"}]}").get(0).id);
  }
  @Test public void missingArrayFails() throws Exception { rejected("{\"message\":\"unavailable\"}"); }
  @Test public void nullRecordFails() throws Exception { rejected("{\"data\":[null]}"); }
  @Test public void missingIdFails() throws Exception { rejected("{\"data\":[{}]}"); }
  @Test public void truncatedJsonFails() throws Exception { rejected("{\"data\":[{\"id\":\"a\"}"); }
  @Test public void trailingDataFails() throws Exception { rejected("{\"data\":[{\"id\":\"a\"}]}{}"); }
  @Test public void duplicateRootFieldFails() throws Exception { rejected("{\"data\":[],\"data\":[]}"); }
  @Test public void duplicateRecordFieldFails() throws Exception { rejected("{\"data\":[{\"id\":\"a\",\"id\":\"b\"}]}"); }
  @Test public void excessiveDepthFails() throws Exception {
    rejected("{\"data\":[{\"id\":\"a\",\"deep\":"+"[".repeat(30)+"0"+"]".repeat(30)+"}]}");
  }
  @Test public void cancelledParsingStopsBeforeAcceptingResult() throws Exception {
    Net.Call c=new Net.Call();c.cancel("fixture");
    try{FeatherlessCatalogue.parse(new ByteArrayInputStream("{\"data\":[]}".getBytes(StandardCharsets.UTF_8)),provider(),new ArrayList<>(),new HashSet<>(),c);fail();}catch(InterruptedIOException expected){}
  }
  @Test public void sixtyThousandRecordsDoNotRequireGiantJsonArray() throws Exception {
    StringBuilder input=new StringBuilder("{\"data\":[");
    for(int i=0;i<60000;i++){if(i>0)input.append(',');input.append("{\"id\":\"fixture/").append(i).append("\"}");}
    input.append("]}");assertEquals(60000,parse(input.toString()).size());
  }
  @Test public void transientErrorsBackOffAndHonourProviderDeadline() {
    assertEquals(15000,FeatherlessCatalogue.retryDelay(new Net.HttpError(429,"busy"),1));
    assertEquals(900000,FeatherlessCatalogue.retryDelay(new Net.HttpError(503,"busy"),100));
    assertEquals(3600000,FeatherlessCatalogue.retryDelay(new Net.HttpError(429,"busy",3600000),1));
    assertEquals(15000,FeatherlessCatalogue.retryDelay(new SocketTimeoutException(),1));
  }
  @Test public void permanentAndMalformedFailuresAreNotLooped() {
    for(int status:new int[]{400,401,402,403,404,422})assertEquals(-1,FeatherlessCatalogue.retryDelay(new Net.HttpError(status,"fixture"),1));
    assertEquals(-1,FeatherlessCatalogue.retryDelay(new Net.HttpError(429,"insufficient_quota"),1));
    assertEquals(-1,FeatherlessCatalogue.retryDelay(new IOException("malformed"),1));
  }
}
