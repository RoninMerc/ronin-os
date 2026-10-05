using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Vanta.Core;
using Vanta.Windows;

namespace Vanta.Tests;
public static partial class Program
{
    static JsonObject BulkFixture(int count, int? total = null)
    {
        var items = new JsonArray();
        for (int i = 0; i < count; i++) items.Add(new JsonObject { ["id"] = i == 0 ? ModelAdapter.DefaultId : "fixture/model-" + i, ["context_length"] = 32768, ["model_class"] = "qwen3.5-27b", ["features"] = new JsonObject { ["tool_use"] = true } });
        var value = new JsonObject { ["data"] = items }; if (total.HasValue) value["total"] = total.Value; return value;
    }
    static async Task FeatherlessContractTests()
    {
        await Test("Featherless normal discovery is one bulk request with no forced pagination", () =>
        {
            string url = ModelAdapter.First(Provider.Get("featherless"));
            True(url.EndsWith("/models?status=active,pending_deploy,not_deployed"));
            var bulk = BulkFixture(1001, 1050); Equal<string?>(null, ModelAdapter.Next(Provider.Get("featherless"), bulk, bulk["data"]!.AsArray(), 1));
        });
        await Test("Public catalogue never reads or transmits the inference key", async () =>
        {
            int keyReads = 0;
            var h = Sync(r => { Equal(HttpMethod.Get, r.Method); True(r.Headers.Authorization == null); True(r.Headers.Contains("X-Title")); True(r.Headers.Contains("HTTP-Referer")); return Json(BulkFixture(1)); });
            using var api = new ProviderApi(_ => { keyReads++; return "private-fixture-key"; }, h);
            await api.CatalogueJsonAsync(Provider.Get("featherless"), ModelAdapter.First(Provider.Get("featherless")), CancellationToken.None);
            Equal(0, keyReads); Equal(1, h.Calls); True(!api.CatalogueDiagnostic("featherless").ToJsonString().Contains("private-fixture-key"));
        });
        await Test("Public catalogue method cannot be used for inference or another host", async () =>
        {
            var h = Sync(_ => throw new Exception("Unsafe request reached transport")); using var api = new ProviderApi(_ => "private-fixture", h);
            foreach (var url in new[] { "https://api.featherless.ai/v1/chat/completions", "https://untrusted.example/v1/models", "http://api.featherless.ai/v1/models" })
                await RejectAsync<VantaException>(() => api.CatalogueJsonAsync(Provider.Get("featherless"), url, CancellationToken.None));
            Equal(0, h.Calls);
        });
        await Test("Count-discrepant bulk response preserves every returned model and the old cache", async () =>
        {
            using var store = new Store(Profile()); store.Save("catalogue", "featherless", new[] { Model(id: "fixture/previous") }, false);
            var registry = new ModelRegistry(store, new CatalogueClock().Options); var h = Sync(_ => Json(BulkFixture(1001, 1029)));
            using var api = new ProviderApi(_ => "fixture", h); await registry.RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None);
            Equal(1, h.Calls); Equal(1002, registry.Snapshot().Count); True(registry.Find("featherless|fixture/previous")!.Status != "removed");
            var observation = store.Read<JsonObject>("catalogue-observation", "featherless")!; Equal(1001L, observation.Num("received")); Equal(1029L, observation.Num("reported")); True(observation.Flag("count_discrepancy"));
            var reopened = new ModelRegistry(store); Equal(1002, reopened.Snapshot().Count); True(reopened.Find("featherless|" + ModelAdapter.DefaultId) != null);
        });
        await Test("Bulk model discovery does not fabricate deployment or plan access", async () =>
        {
            using var store = new Store(Profile()); using var api = new ProviderApi(_ => "fixture", Sync(_ => Json(BulkFixture(1)))); var registry = new ModelRegistry(store);
            await registry.RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None);
            var model = registry.Find("featherless|" + ModelAdapter.DefaultId)!; Equal("listed", model.Status); True(model.Raw["available_on_current_plan"] == null); True(model.EndpointNote.Contains("does not supply deployment status"));
        });
        await Test("Explicit server pagination still advances and honours returned page size", () =>
        {
            var value = BulkFixture(2); value["pagination"] = new JsonObject { ["current_page"] = 1, ["per_page"] = 2, ["total_pages"] = 3, ["total_items"] = 6 };
            string next = ModelAdapter.Next(Provider.Get("featherless"), value, value["data"]!.AsArray(), 1)!;
            True(next.Contains("per_page=2") && next.Contains("page=2"));
            value["pagination"]!["current_page"] = 3;
            Reject<VantaException>(() => ModelAdapter.Next(Provider.Get("featherless"), value, value["data"]!.AsArray(), 2));
        });
        await Test("Repeated final page is rejected rather than excused as a count discrepancy", async () =>
        {
            using var store = new Store(Profile()); int calls = 0;
            using var api = new ProviderApi(_ => "fixture", Sync(_ => Json(new JsonObject { ["data"] = new JsonArray(new JsonObject { ["id"] = "fixture/same" }), ["pagination"] = new JsonObject { ["current_page"] = ++calls, ["total_pages"] = 2, ["total_items"] = 2 } })));
            await RejectAsync<VantaException>(() => new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None)); Equal(2, calls);
        });
        await Test("Empty or malformed Featherless bulk response cannot erase the saved catalogue", async () =>
        {
            foreach (var response in new[] { BulkFixture(0, 50000), new JsonObject { ["total"] = 50000 }, new JsonObject { ["data"] = new JsonArray(new JsonObject { ["id"] = "" }) } })
            {
                using var store = new Store(Profile()); store.Save("catalogue", "featherless", new[] { Model() }, false); var registry = new ModelRegistry(store);
                using var api = new ProviderApi(_ => "fixture", Sync(_ => Json(response)));
                await RejectAsync<VantaException>(() => registry.RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None)); Equal(1, registry.Snapshot().Count);
            }
        });
        await Test("A 0.4.1 staged page chain migrates to one bulk request without bypassing cooldown", async () =>
        {
            using var store = new Store(Profile()); var provider = Provider.Get("featherless"); var clock = new CatalogueClock(); var due = clock.Time.AddMinutes(4);
            store.Save("catalogue-sync", provider.Id, new CatalogueCheckpoint { Identity = CatalogueReader.Identity(store, provider), Page = 35, Url = provider.BaseUrl + "/models?per_page=1000&page=35", Started = clock.Time, NextAttempt = due });
            string url = ""; using var api = new ProviderApi(_ => "fixture", Sync(r => { True(clock.Time >= due); url = r.RequestUri!.AbsoluteUri; return Json(BulkFixture(1)); }));
            await new ModelRegistry(store, clock.Options).RefreshAsync(provider, api, null, CancellationToken.None); True(!url.Contains("page="));
        });
        await Test("Independent key check uses authenticated plan GET and never buys inference", async () =>
        {
            var h = Sync(r => { Equal(HttpMethod.Get, r.Method); Equal("/v1/plan", r.RequestUri!.AbsolutePath); Equal("Bearer", r.Headers.Authorization!.Scheme); Equal("fixture-secret", r.Headers.Authorization.Parameter); return Json(new JsonObject { ["type"] = "developer" }); });
            using var api = new ProviderApi(_ => "fixture-secret", h); await api.CheckFeatherlessKeyAsync(CancellationToken.None); Equal(1, h.Calls);
            using var missing = new ProviderApi(_ => null, Sync(_ => throw new Exception("No key should be sent"))); await RejectAsync<VantaException>(() => missing.CheckFeatherlessKeyAsync(CancellationToken.None));
        });
        await Test("Normal paid generation still carries the user's key with no automatic replay", async () =>
        {
            var h = Sync(r => { Equal("fixture-secret", r.Headers.Authorization!.Parameter); return CatalogueError(); }); using var api = new ProviderApi(_ => "fixture-secret", h);
            await HttpFailure(api, HttpMethod.Post); Equal(1, h.Calls);
        });
        await Test("HTTP diagnostic identifies catalogue request and response without generic inference error", async () =>
        {
            var h = Sync(_ => { var r = CatalogueError(retry: "60"); r.Headers.Add("x-request-id", "fixture-request-id"); return r; }); using var api = new ProviderApi(_ => "fixture-secret", h);
            await RejectAsync<ProviderHttpException>(() => api.CatalogueJsonAsync(Provider.Get("featherless"), ModelAdapter.First(Provider.Get("featherless")), CancellationToken.None));
            var diagnostic = api.CatalogueDiagnostic("featherless"); Equal(429L, diagnostic.Num("http_status")); Equal("fixture-request-id", diagnostic.Str("request_id")); True(diagnostic.Str("error").Contains("read-only")); True(!diagnostic.ToJsonString().Contains("fixture-secret"));
        });
        await Test("Catalogue storage migrates legacy records and preserves all fields and preferences", () =>
        {
            using var store = new Store(Profile()); var original = Model(); original.Favourite = true; store.Save("catalogue", "featherless", new[] { original }, false);
            Equal(original.Id, CatalogueStorage.Read(store, "catalogue", "featherless", false)!.Single().Id);
            var rows = Enumerable.Range(0, 600).Select(i => Model(id: "fixture/" + i)).ToList(); rows[0].Favourite = true;
            CatalogueStorage.Write(store, "catalogue", "featherless", rows, false); True(!store.Ids("catalogue").Contains("featherless")); Equal(3, store.Ids("catalogue-chunk").Count);
            var loaded = CatalogueStorage.Read(store, "catalogue", "featherless", false)!; Equal(600, loaded.Count); True(loaded[0].Favourite); Equal(rows[^1].Raw.ToJsonString(), loaded[^1].Raw.ToJsonString());
            CatalogueStorage.Write(store, "catalogue", "featherless", rows.Take(1).ToArray(), false); Equal(1, store.Ids("catalogue-chunk").Count);
        });
        await Test("Catalogue larger than the 48 MiB single-record limit saves and reopens in shards", () =>
        {
            using var store = new Store(Profile()); var rows = Enumerable.Range(0, 4096).Select(i => { var m = Model(id: "fixture/large-" + i); m.Description = new string('x', 14000); return m; }).ToList();
            long bytes = JsonSerializer.SerializeToUtf8Bytes(rows, JsonEx.Options).LongLength; True(bytes > 48L * 1024 * 1024);
            CatalogueStorage.Write(store, "catalogue", "featherless", rows, false); var restored = CatalogueStorage.Read(store, "catalogue", "featherless", false)!;
            Equal(rows.Count, restored.Count); Equal(rows[^1].Description, restored[^1].Description); Measurements["sharded_test_total_serialised_bytes"] = bytes;
        });
        await Test("Unpublished shard generation never replaces the published snapshot", () =>
        {
            using var store = new Store(Profile()); CatalogueStorage.Write(store, "catalogue", "featherless", new[] { Model() }, false);
            var before = store.Read<CatalogueStorage.Manifest>("catalogue-manifest", "featherless", false)!;
            store.Save("catalogue-chunk", "unpublished-fixture", new[] { Model(id: "fixture/unpublished") }, false);
            Equal(ModelAdapter.DefaultId, CatalogueStorage.Read(store, "catalogue", "featherless", false)!.Single().Id);
            Equal(before.Generation, store.Read<CatalogueStorage.Manifest>("catalogue-manifest", "featherless", false)!.Generation);
        });
        if (Environment.GetEnvironmentVariable("VANTA_LIVE_CATALOGUE_TEST") == "1") await LiveFeatherlessCatalogue();
    }
    sealed class LiveCatalogueHandler : DelegatingHandler
    {
        public int Calls; public int Bytes; public int Returned; public long Reported; public HashSet<string> Ids = new(StringComparer.Ordinal);
        public LiveCatalogueHandler() : base(new SocketsHttpHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate, ConnectTimeout = TimeSpan.FromSeconds(25) }) { }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (Interlocked.Increment(ref Calls) != 1) throw new VantaException("Live verification allows one catalogue GET only; no blind retry probe.");
            True(request.Method == HttpMethod.Get && request.RequestUri!.Host == "api.featherless.ai" && request.RequestUri.AbsolutePath == "/v1/models"); True(request.Headers.Authorization == null);
            var response = await base.SendAsync(request, ct); response.EnsureSuccessStatusCode();
            await response.Content.LoadIntoBufferAsync(32 * 1024 * 1024);
            var raw = await response.Content.ReadAsByteArrayAsync(ct); Bytes = raw.Length;
            var json = JsonNode.Parse(raw)!.AsObject(); Returned = json["data"]!.AsArray().Count; Reported = json.Num("total", -1);
            foreach (var item in json["data"]!.AsArray()) Ids.Add(item.Str("id"));
            File.WriteAllBytes(Path.Combine(Evidence, "featherless-live-catalogue.json"), raw);
            return response;
        }
    }
    static async Task LiveFeatherlessCatalogue()
    {
        await Test("LIVE Featherless bulk catalogue: one unauthenticated GET through actual job, parser, persistence, reopen and native model grid", async () =>
        {
            string path = Profile(); var handler = new LiveCatalogueHandler(); var clock = Stopwatch.StartNew();
            services = new VantaServices(path, handler); string jobId = "";
            try
            {
                var job = services.Refresh(Provider.Get("featherless")); jobId = job.Id;
                var completed = await Finish(services.Jobs, job.Id, 180); Equal("Complete", completed.State); Equal(1, handler.Calls);
                var rows = services.Models.Snapshot(); True(rows.Count > 10000); Equal(handler.Ids.Count, rows.Count); True(handler.Ids.SetEquals(rows.Select(m => m.Id)));
                True(services.Models.Find("featherless|" + ModelAdapter.DefaultId) != null);
                Measurements["live_featherless_pipeline_seconds"] = clock.Elapsed.TotalSeconds;
                Measurements["live_featherless_http_gets"] = handler.Calls; Measurements["live_featherless_response_bytes"] = handler.Bytes;
                Measurements["live_featherless_received"] = handler.Returned; Measurements["live_featherless_reported"] = handler.Reported;
                Measurements["live_featherless_unique_saved"] = rows.Count;
                Measurements["live_featherless_expanded_serialised_bytes"] = JsonSerializer.SerializeToUtf8Bytes(rows, JsonEx.Options).LongLength;
                var observation = services.Store.Read<JsonObject>("catalogue-observation", "featherless")!;
                File.WriteAllText(Path.Combine(Evidence, "featherless-live-observation.json"), observation.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
                await services.Jobs.StopAsync(); services.Dispose();
                services = new VantaServices(path, Sync(_ => throw new Exception("Reopen/grid must not make another request")));
                var reopened = services.Models.Snapshot(); Equal(rows.Count, reopened.Count); True(handler.Ids.SetEquals(reopened.Select(m => m.Id)));
                services.Preferences.TrayEnabled = false; window = new MainWindow(services) { TestLifetime = true, Width = 1440, Height = 920, Left = 0, Top = 0 }; Application.Current.MainWindow = window;
                window.Show(); window.Navigate("Models"); await Layout(); var page = (ModelsPage)Page(); await page.ApplyAsync(); await Layout();
                Equal(rows.Count, page.Catalogue.Items.Count); Shot("featherless-live-all-models");
                page.SetSearch(ModelAdapter.DefaultId); await page.ApplyAsync(); await Layout(); True(page.Catalogue.Items.Cast<ModelRecord>().Any(m => m.Id == ModelAdapter.DefaultId)); Shot("featherless-live-huihui-search");
                window.Navigate("Activity"); await Layout(); ((ActivityPage)Page()).Select(jobId); await Layout(); Shot("featherless-live-complete");
                window.Navigate("Settings"); await Layout(); True(Descendants<Button>(window).Any(b => b.Content?.ToString() == "Check saved key")); Shot("featherless-key-check-settings");
            }
            finally { if (services != null) { await services.Jobs.StopAsync(); if (window?.IsVisible == true) window.Close(); services.Dispose(); } services = null; window = null; }
        });
    }
}
