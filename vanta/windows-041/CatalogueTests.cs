using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Vanta.Core;

namespace Vanta.Tests;
public static partial class Program
{
    sealed class CatalogueClock
    {
        public DateTimeOffset Time = DateTimeOffset.UtcNow;
        public List<TimeSpan> Delays = new();
        public CatalogueSyncOptions Options => new()
        {
            Now = () => Time,
            Delay = (delay, ct) => { ct.ThrowIfCancellationRequested(); Delays.Add(delay); Time += delay; return Task.CompletedTask; },
            PageSpacing = TimeSpan.Zero,
            JitterSeconds = () => 0
        };
    }
    static HttpResponseMessage CataloguePage(int page = 1, int pages = 1) => Json(new JsonObject
    {
        ["data"] = new JsonArray(new JsonObject { ["id"] = "fixture/page-" + page, ["type"] = "text" }),
        ["pagination"] = new JsonObject { ["current_page"] = page, ["total_pages"] = pages, ["total_items"] = pages }
    });
    static HttpResponseMessage CatalogueError(int status = 429, string? retry = null, string code = "rate_limit_exceeded")
    {
        var response = Json(new JsonObject { ["error"] = new JsonObject { ["code"] = code, ["message"] = "Fixture provider error" } }, (HttpStatusCode)status);
        if (retry != null) response.Headers.TryAddWithoutValidation("Retry-After", retry);
        return response;
    }
    static async Task<ProviderHttpException> HttpFailure(ProviderApi api, HttpMethod method)
    {
        try { await api.JsonAsync(Provider.Get("featherless"), method, Provider.Get("featherless").BaseUrl + "/models", null, CancellationToken.None); }
        catch (ProviderHttpException error) { return error; }
        throw new Exception("Expected HTTP failure");
    }
    static async Task CatalogueTests()
    {
        await Test("Catalogue 429 then success retries exactly the same URL without a new task", async () =>
        {
            using var store = new Store(Profile()); var clock = new CatalogueClock(); var urls = new List<string>(); var reports = new List<CatalogueProgress>();
            var h = Sync(r => { urls.Add(r.RequestUri!.AbsoluteUri); return urls.Count == 1 ? CatalogueError() : CataloguePage(); });
            using var api = new ProviderApi(_ => "fixture", h); var registry = new ModelRegistry(store, clock.Options);
            await registry.RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None, reports.Add);
            Equal(2, h.Calls); Equal(urls[0], urls[1]); Equal(1, registry.Snapshot().Count);
            True(reports.Any(r => r.Stage == "Waiting for provider" && r.Attempts == 1)); True(clock.Delays.Sum(d => d.TotalSeconds) >= 15);
        });
        await Test("Retry-After delta seconds is preserved as a minimum, not a capped maximum", async () =>
        {
            using var api = new ProviderApi(_ => "fixture", Sync(_ => CatalogueError(retry: "3600")));
            var before = DateTimeOffset.UtcNow; var error = await HttpFailure(api, HttpMethod.Get);
            True(error.RetryNotBefore >= before.AddHours(1));
            var due = CatalogueReader.RetryTime(error, 1, new CatalogueSyncOptions()); True(due >= error.RetryNotBefore);
            True(!error.Recovery.Contains("generation was automatically"));
        });
        await Test("Retry-After HTTP date is parsed accurately", async () =>
        {
            var due = DateTimeOffset.UtcNow.AddHours(2); due = DateTimeOffset.FromUnixTimeSeconds(due.ToUnixTimeSeconds());
            using var api = new ProviderApi(_ => "fixture", Sync(_ => CatalogueError(retry: due.ToString("R"))));
            Equal(due, (await HttpFailure(api, HttpMethod.Get)).RetryNotBefore!.Value);
        });
        await Test("Malformed Retry-After falls back to bounded exponential delay", async () =>
        {
            using var api = new ProviderApi(_ => "fixture", Sync(_ => CatalogueError(retry: "not-a-date")));
            var error = await HttpFailure(api, HttpMethod.Get); Equal<DateTimeOffset?>(null, error.RetryNotBefore);
            var clock = new CatalogueClock(); Equal(TimeSpan.FromSeconds(15), CatalogueReader.RetryTime(error, 1, clock.Options) - clock.Time);
            Equal(TimeSpan.FromMinutes(15), CatalogueReader.RetryTime(error, 99, clock.Options) - clock.Time);
        });
        await Test("Repeated 429 responses back off and settle at one read per fifteen minutes", async () =>
        {
            using var store = new Store(Profile()); var clock = new CatalogueClock(); var starts = new List<DateTimeOffset>();
            using var api = new ProviderApi(_ => "fixture", Sync(_ => { starts.Add(clock.Time); return starts.Count <= 9 ? CatalogueError() : CataloguePage(); }));
            var registry = new ModelRegistry(store, clock.Options); await registry.RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None);
            Equal(10, starts.Count); Equal(TimeSpan.FromSeconds(15), starts[1] - starts[0]); Equal(TimeSpan.FromMinutes(15), starts[^1] - starts[^2]);
        });
        foreach (int status in new[] { 400, 401, 402, 403, 404, 422 })
            await Test($"Catalogue HTTP {status} is not blindly retried", async () =>
            {
                using var store = new Store(Profile()); var h = Sync(_ => CatalogueError(status)); using var api = new ProviderApi(_ => "fixture", h);
                await RejectAsync<ProviderHttpException>(() => new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None)); Equal(1, h.Calls);
            });
        await Test("Billing/quota 429 requires account action, not an endless retry", async () =>
        {
            using var store = new Store(Profile()); var h = Sync(_ => CatalogueError(code: "insufficient_quota")); using var api = new ProviderApi(_ => "fixture", h);
            await RejectAsync<ProviderHttpException>(() => new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None)); Equal(1, h.Calls);
        });
        foreach (int status in new[] { 408, 500, 502, 503, 504 })
            await Test($"Catalogue transient HTTP {status} safely recovers", async () =>
            {
                using var store = new Store(Profile()); int calls = 0; using var api = new ProviderApi(_ => "fixture", Sync(_ => ++calls == 1 ? CatalogueError(status) : CataloguePage()));
                await new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None); Equal(2, calls);
            });
        await Test("Connection loss and request timeout recover for catalogue GET only", async () =>
        {
            using var store = new Store(Profile()); int calls = 0; using var api = new ProviderApi(_ => "fixture", Sync(_ => ++calls switch { 1 => throw new HttpRequestException("fixture connection lost"), 2 => throw new TaskCanceledException("fixture request timeout"), _ => CataloguePage() }));
            await new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None); Equal(3, calls);
        });
        await Test("Paid POST remains single-attempt even with Retry-After", async () =>
        {
            var h = Sync(_ => CatalogueError(retry: "1")); using var api = new ProviderApi(_ => "fixture", h); var error = await HttpFailure(api, HttpMethod.Post);
            Equal(1, h.Calls); True(error.Recovery.Contains("No generation was automatically repeated"));
        });
        await Test("Twenty simultaneous refresh clicks coalesce into one catalogue task and read", async () =>
        {
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously); var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var h = new Handler(async (_, ct) => { entered.TrySetResult(); await release.Task.WaitAsync(ct); return CataloguePage(); });
            using var s = Service(h);
            try
            {
                var jobs = await Task.WhenAll(Enumerable.Range(0, 20).Select(_ => Task.Run(() => s.Refresh(Provider.Get("featherless")))));
                await entered.Task.WaitAsync(TimeSpan.FromSeconds(10)); Equal(1, jobs.Select(j => j.Id).Distinct().Count()); Equal(1, h.Calls);
                release.TrySetResult(); Equal("Complete", (await Finish(s.Jobs, jobs[0].Id)).State);
                Equal(jobs[0].Id, s.Refresh(Provider.Get("featherless")).Id); Equal(1, h.Calls);
            }
            finally { release.TrySetResult(); await s.Jobs.StopAsync(); }
        });
        await Test("Cancellation during provider cooldown stops cleanly without another request", async () =>
        {
            using var store = new Store(Profile()); using var cancel = new CancellationTokenSource(); int calls = 0;
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var options = new CatalogueSyncOptions { Delay = (delay, ct) => { entered.TrySetResult(); return Task.Delay(Timeout.InfiniteTimeSpan, ct); } };
            using var api = new ProviderApi(_ => "fixture", Sync(_ => { calls++; return CatalogueError(); }));
            var task = new ModelRegistry(store, options).RefreshAsync(Provider.Get("featherless"), api, null, cancel.Token);
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(10)); cancel.Cancel(); await RejectAsync<OperationCanceledException>(() => task); Equal(1, calls);
            True(store.Read<CatalogueCheckpoint>("catalogue-sync", "featherless")?.NextAttempt != null);
        });
        await Test("Restart resumes page two, respects saved cooldown, and preserves the old cache", async () =>
        {
            string path = Profile(); var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously); int initialCalls = 0; DateTimeOffset due;
            using (var store = new Store(path))
            {
                store.SetKey("featherless", "qa-fixture-no-live-account"); store.Save("catalogue", "featherless", new[] { Model() }, false);
            }
            var hold = new CatalogueSyncOptions { PageSpacing = TimeSpan.Zero, Delay = (_, ct) => { entered.TrySetResult(); return Task.Delay(Timeout.InfiniteTimeSpan, ct); } };
            using (var s = new VantaServices(path, Sync(_ => ++initialCalls == 1 ? CataloguePage(1, 2) : CatalogueError(retry: "120")), hold))
            {
                var job = s.Refresh(Provider.Get("featherless")); await entered.Task.WaitAsync(TimeSpan.FromSeconds(10));
                Equal("Waiting for provider", s.Jobs.Get(job.Id)!.State); True(s.Models.Find("featherless|" + ModelAdapter.DefaultId) != null); Equal(1, s.Models.Snapshot().Count);
                var checkpoint = s.Store.Read<CatalogueCheckpoint>("catalogue-sync", "featherless")!; Equal(2, checkpoint.Page); due = checkpoint.NextAttempt!.Value;
                await s.Jobs.StopAsync(); Equal("Waiting for provider", s.Jobs.Get(job.Id)!.State);
            }
            var clock = new CatalogueClock(); var urls = new List<string>();
            using (var s = new VantaServices(path, Sync(r => { urls.Add(r.RequestUri!.AbsoluteUri); True(clock.Time >= due, "Provider cooldown was bypassed on restart"); return CataloguePage(2, 2); }), clock.Options))
            {
                var job = s.Jobs.Snapshot().Single(j => j.Type == "catalogue"); Equal("Complete", (await Finish(s.Jobs, job.Id)).State);
                Equal(1, urls.Count); True(urls[0].Contains("page=2")); True(s.Models.Find("featherless|fixture/page-1") != null); True(s.Models.Find("featherless|fixture/page-2") != null);
                Equal<CatalogueCheckpoint?>(null, s.Store.Read<CatalogueCheckpoint>("catalogue-sync", "featherless"));
                Equal(0, s.Store.Ids("catalogue-page").Count); await s.Jobs.StopAsync();
            }
        });
        await Test("Legacy duplicate errors are consolidated without deleting their diagnostics", async () =>
        {
            string path = Profile();
            using (var store = new Store(path))
            {
                store.SetKey("featherless", "fixture"); using var jobs = new JobEngine(store);
                for (int n = 0; n < 3; n++) { var j = jobs.Create("catalogue", "Featherless AI catalogue", "featherless", new() { ["provider"] = "featherless" }); jobs.Update(j.Id, x => { x.State = "Action required"; x.Error = "The provider's rate or concurrency limit was reached."; }); }
                var paid = jobs.Create("image", "Paid generation", "venice|image", new()); jobs.Update(paid.Id, x => { x.State = "Action required"; x.RequestInFlight = true; });
            }
            var h = Sync(_ => CataloguePage()); using var s = new VantaServices(path, h, new CatalogueClock().Options);
            var active = s.Jobs.Snapshot().Single(j => j.Type == "catalogue" && j.State != "Superseded"); Equal("Complete", (await Finish(s.Jobs, active.Id)).State);
            Equal(2, s.Jobs.Snapshot().Count(j => j.State == "Superseded" && j.Events.Any(e => e.Stage == "Previous error")));
            Equal("Action required", s.Jobs.Snapshot().Single(j => j.Type == "image").State); Equal(1, h.Calls); await s.Jobs.StopAsync();
        });
        await Test("Cooling catalogue does not consume either generation execution slot", async () =>
        {
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var hold = new CatalogueSyncOptions { Delay = (_, ct) => { entered.TrySetResult(); return Task.Delay(Timeout.InfiniteTimeSpan, ct); } };
            using var s = new VantaServices(Profile(), Sync(_ => CatalogueError()), hold);
            var catalogue = s.Refresh(Provider.Get("featherless")); await entered.Task.WaitAsync(TimeSpan.FromSeconds(10));
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously); int started = 0;
            var both = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var jobs = Enumerable.Range(0, 2).Select(n => s.Jobs.Create("fixture", "Generation slot " + n, "fixture", new())).ToArray();
            try
            {
                foreach (var job in jobs) s.Jobs.Start(job.Id, async c => { if (Interlocked.Increment(ref started) == 2) both.TrySetResult(); await release.Task.WaitAsync(c.Token); c.Complete(new() { ["text"] = "fixture" }); });
                await both.Task.WaitAsync(TimeSpan.FromSeconds(10)); Equal(2, started);
                release.TrySetResult(); foreach (var job in jobs) Equal("Complete", (await Finish(s.Jobs, job.Id)).State);
                s.Jobs.Cancel(catalogue.Id); Equal("Cancelled", (await Finish(s.Jobs, catalogue.Id)).State);
            }
            finally { release.TrySetResult(); await s.Jobs.StopAsync(); }
        });
        await Test("Changing credentials invalidates staged account-specific catalogue pages", async () =>
        {
            using var store = new Store(Profile()); var p = Provider.Get("featherless"); store.SetKey(p.Id, "old-fixture");
            var old = new CatalogueCheckpoint { Identity = CatalogueReader.Identity(store, p), Page = 2, Url = p.BaseUrl + "/models?page=2", Started = DateTimeOffset.UtcNow };
            store.Save("catalogue-sync", p.Id, old); store.Save("catalogue-page", p.Id + ":" + old.Session + ":1", new[] { Model() }); store.SetKey(p.Id, "new-fixture");
            string url = ""; using var api = new ProviderApi(store.Key, Sync(r => { url = r.RequestUri!.AbsoluteUri; return CataloguePage(); }));
            await new ModelRegistry(store, new CatalogueClock().Options).RefreshAsync(p, api, null, CancellationToken.None); True(url.Contains("page=1"));
        });
        await Test("User-cancelled catalogues stay cancelled after reopen", async () =>
        {
            string path = Profile(); using (var store = new Store(path)) { store.SetKey("featherless", "fixture"); using var jobs = new JobEngine(store); var j = jobs.Create("catalogue", "Cancelled", "featherless", new()); jobs.Cancel(j.Id); }
            var h = Sync(_ => throw new Exception("Cancelled task must not call provider")); using var s = new VantaServices(path, h); await Task.Delay(50);
            Equal(0, h.Calls); Equal("Cancelled", s.Jobs.Snapshot().Single().State); await s.Jobs.StopAsync();
        });
    }
}
