using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Vanta.Core;

/// <summary>Only catalogue GETs use this policy. Paid requests never enter this retry loop.</summary>
public sealed class CatalogueSyncOptions
{
    public Func<DateTimeOffset> Now { get; init; } = () => DateTimeOffset.UtcNow;
    public Func<TimeSpan, CancellationToken, Task> Delay { get; init; } = Task.Delay;
    public TimeSpan PageSpacing { get; init; } = TimeSpan.FromSeconds(2);
    public TimeSpan FirstRetry { get; init; } = TimeSpan.FromSeconds(15);
    public TimeSpan MaximumRetry { get; init; } = TimeSpan.FromMinutes(15);
    public Func<double> JitterSeconds { get; init; } = () => Random.Shared.NextDouble() * 2;
}
public sealed record CatalogueProgress(string Stage, string Action, int Page, int Count, DateTimeOffset? RetryAt = null, int Attempts = 0);
public sealed class CatalogueCheckpoint
{
    public string Identity { get; set; } = "";
    public string Session { get; set; } = Guid.NewGuid().ToString("N");
    public int Page { get; set; } = 1;
    public string? Url { get; set; }
    public List<string> Seen { get; set; } = [];
    public DateTimeOffset Started { get; set; }
    public DateTimeOffset? NextAttempt { get; set; }
    public int Failures { get; set; }
}

/// <summary>Serial, paced, durable catalogue discovery. Partial pages never replace the live cache.</summary>
public sealed class CatalogueReader(Store store, CatalogueSyncOptions options)
{
    private readonly ConcurrentDictionary<string, SemaphoreSlim> lanes = new();
    public static string Identity(Store store, Provider p) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(p.Id + "\n" + p.BaseUrl + "\n" + (store.Key(p.Id) ?? ""))));
    public static bool Temporary(Exception error, CancellationToken ct) => !ct.IsCancellationRequested && (error is HttpRequestException || error is OperationCanceledException || error is ProviderHttpException h && h.Status is 408 or 429 or 500 or 502 or 503 or 504 && !h.AccountActionRequired);
    public static DateTimeOffset RetryTime(ProviderHttpException? error, int failures, CatalogueSyncOptions options)
    {
        double seconds = Math.Min(options.MaximumRetry.TotalSeconds, options.FirstRetry.TotalSeconds * Math.Pow(2, Math.Clamp(failures - 1, 0, 20)));
        var due = options.Now().AddSeconds(Math.Max(0.01, seconds) + Math.Max(0, options.JitterSeconds()));
        return error?.RetryNotBefore is DateTimeOffset provider && provider > due ? provider : due;
    }
    private void Save(Provider p, CatalogueCheckpoint checkpoint) => store.Save("catalogue-sync", p.Id, checkpoint);
    private void ClearPages(Provider p)
    {
        foreach (var id in store.Ids("catalogue-page").Where(id => id.StartsWith(p.Id + ":", StringComparison.Ordinal))) store.Delete("catalogue-page", id);
    }
    private CatalogueCheckpoint Fresh(Provider p, DateTimeOffset? cooldown = null)
    {
        // Replace the pointer before cleanup. An interruption cannot expose orphaned pages as a result.
        var checkpoint = new CatalogueCheckpoint { Identity = Identity(store, p), Url = ModelAdapter.First(p), Started = options.Now(), NextAttempt = cooldown };
        Save(p, checkpoint); ClearPages(p); return checkpoint;
    }
    private string PageKey(Provider p, CatalogueCheckpoint checkpoint, int page) => p.Id + ":" + checkpoint.Session + ":" + page;
    public async Task RefreshAsync(ModelRegistry registry, Provider p, ProviderApi api, Action<CatalogueProgress>? progress, CancellationToken ct)
    {
        var lane = lanes.GetOrAdd(p.Id, _ => new SemaphoreSlim(1, 1));
        await lane.WaitAsync(ct);
        try
        {
            var checkpoint = store.Read<CatalogueCheckpoint>("catalogue-sync", p.Id);
            if (checkpoint == null || checkpoint.Identity != Identity(store, p)) checkpoint = Fresh(p);
            else if (options.Now() - checkpoint.Started > TimeSpan.FromHours(6)) checkpoint = Fresh(p, checkpoint.NextAttempt);
            var incoming = new Dictionary<string, ModelRecord>(StringComparer.Ordinal);
            for (int page = 1; page < checkpoint.Page; page++)
            {
                var saved = store.Read<List<ModelRecord>>("catalogue-page", PageKey(p, checkpoint, page));
                if (saved == null) { checkpoint = Fresh(p, checkpoint.NextAttempt); incoming.Clear(); break; }
                foreach (var row in saved) incoming.TryAdd(row.Id, row);
            }
            while (checkpoint.Url != null)
            {
                ct.ThrowIfCancellationRequested();
                if (checkpoint.Identity != Identity(store, p)) { checkpoint = Fresh(p); incoming.Clear(); }
                if (checkpoint.NextAttempt is DateTimeOffset due && due > options.Now())
                {
                    string stage = checkpoint.Failures > 0 ? "Waiting for provider" : "Pacing catalogue";
                    progress?.Invoke(new(stage, $"{p.Name}: next catalogue read at {due.LocalDateTime:T}. {incoming.Count:N0} models checkpointed; the last complete cache remains usable. No generation is being submitted.", checkpoint.Page, incoming.Count, due, checkpoint.Failures));
                    while (due > options.Now()) await options.Delay(TimeSpan.FromSeconds(Math.Min(60, (due - options.Now()).TotalSeconds)), ct);
                }
                ct.ThrowIfCancellationRequested();
                if (checkpoint.Page > 1000 || checkpoint.Seen.Contains(checkpoint.Url)) throw new VantaException("The provider repeated its catalogue pages.", "Your last complete cache was not replaced.");
                progress?.Invoke(new("Discovering models", $"Reading {p.Name} catalogue · page {checkpoint.Page:N0} · {incoming.Count:N0} models", checkpoint.Page, incoming.Count));
                JsonObject json;
                try { json = await api.JsonAsync(p, HttpMethod.Get, checkpoint.Url!, null, ct); }
                catch (Exception error) when (Temporary(error, ct))
                {
                    checkpoint.Failures = Math.Min(100000, checkpoint.Failures + 1);
                    checkpoint.NextAttempt = RetryTime(error as ProviderHttpException, checkpoint.Failures, options);
                    Save(p, checkpoint); // Durable BEFORE a wait or another request, including across restarts.
                    continue;
                }
                ct.ThrowIfCancellationRequested();
                if (json["data"] is not JsonArray items) throw new VantaException("The provider did not return a model catalogue.");
                var pageRows = new List<ModelRecord>(); int novel = 0;
                foreach (var value in items)
                {
                    if (value is not JsonObject item) throw new VantaException("A catalogue record could not be read.");
                    var model = ModelAdapter.Parse(p, item); pageRows.Add(model); if (incoming.TryAdd(model.Id, model)) novel++;
                }
                var next = ModelAdapter.Next(p, json, items, checkpoint.Page);
                if (next != null && novel == 0) throw new VantaException("The provider repeated a model page.", "The previous complete cache is safe.");
                if (next == null)
                {
                    var meta = json["pagination"] as JsonObject ?? json["meta"] as JsonObject ?? json;
                    long declared = meta.Num("total_items", meta.Num("total", -1));
                    if (declared >= 0 && declared != incoming.Count) throw new VantaException("The provider returned an incomplete catalogue.", "The previous complete cache has been retained; refresh later.");
                }
                store.Save("catalogue-page", PageKey(p, checkpoint, checkpoint.Page), pageRows);
                checkpoint.Seen.Add(checkpoint.Url!); checkpoint.Page++; checkpoint.Url = next; checkpoint.Failures = 0;
                checkpoint.NextAttempt = options.Now() + options.PageSpacing; Save(p, checkpoint);
            }
            ct.ThrowIfCancellationRequested();
            if (checkpoint.Identity != Identity(store, p)) throw new VantaException("The provider credentials changed during discovery.", "Refresh the catalogue using the new credentials. The previous cache was retained.");
            registry.Commit(p, incoming.Values.ToList()); registry.ApplyPreferences();
            store.Save("catalogue-success", p.Id, new JsonObject { ["identity"] = checkpoint.Identity, ["at"] = options.Now().ToString("O") });
            store.Delete("catalogue-sync", p.Id); ClearPages(p);
        }
        catch (Exception error) when (error is not OperationCanceledException && error is not HttpRequestException && error is not ProviderHttpException)
        {
            // Invalid/inconsistent JSON must not poison the next deliberate refresh.
            store.Delete("catalogue-sync", p.Id); ClearPages(p); throw;
        }
        finally { lane.Release(); }
    }
}

public sealed partial class VantaServices
{
    private readonly object catalogueGate = new();
    private JobRecord StartCatalogue(Provider provider, string? requestedId = null)
    {
        lock (catalogueGate)
        {
            var jobs = Jobs.Snapshot().Where(j => j.Type == "catalogue" && j.ModelKey == provider.Id && j.State != "Superseded").ToList();
            var active = jobs.FirstOrDefault(j => Jobs.IsExecuting(j.Id));
            if (active != null) return active;
            var job = jobs.FirstOrDefault(j => j.Id == requestedId) ?? jobs.FirstOrDefault(j => !j.Terminal)
                ?? jobs.FirstOrDefault(j => j.State != "Complete") ?? jobs.FirstOrDefault();
            if (job?.State == "Complete")
            {
                var success = Store.Read<JsonObject>("catalogue-success", provider.Id);
                if (success.Str("identity") == CatalogueReader.Identity(Store, provider) && DateTimeOffset.TryParse(success.Str("at"), out var at) && DateTimeOffset.UtcNow - at < TimeSpan.FromMinutes(1)) return job;
            }
            job ??= Jobs.Create("catalogue", provider.Name + " catalogue", provider.Id, new() { ["provider"] = provider.Id });
            foreach (var duplicate in jobs.Where(j => j.Id != job.Id && j.State != "Complete"))
                Jobs.Update(duplicate.Id, j => { j.State = "Superseded"; j.RequestInFlight = false; j.Progress = ProgressValue.Unknown("Superseded", "Catalogue refresh is consolidated into task " + job.Id + ". Previous diagnostics are retained below."); if (j.Error.Length > 0) j.Events.Add(new(DateTimeOffset.UtcNow, "Previous error", j.Error)); j.Error = ""; });
            Jobs.Start(job.Id, async c =>
            {
                await Models.RefreshAsync(provider, Api, null, c.Token, report =>
                {
                    c.Token.ThrowIfCancellationRequested();
                    Jobs.Update(c.Id, j =>
                    {
                        j.State = report.Stage == "Waiting for provider" ? "Waiting for provider" : "Running";
                        j.Error = ""; j.RequestInFlight = false; j.Progress = ProgressValue.Unknown(report.Stage, report.Action);
                        j.Remote["next_catalogue_read"] = report.RetryAt?.ToString("O"); j.Remote["catalogue_page"] = report.Page; j.Remote["catalogue_retry"] = report.Attempts;
                        j.Events.Add(new(DateTimeOffset.UtcNow, report.Stage, report.Action)); if (j.Events.Count > 300) j.Events.RemoveRange(0, j.Events.Count - 300);
                    });
                });
                c.Complete(new() { ["text"] = $"{Models.Snapshot().Count(m => m.ProviderId == provider.Id):N0} catalogue entries saved. Account permissions and endpoint compatibility still apply." });
            });
            return Jobs.Get(job.Id)!;
        }
    }
    private void RecoverCatalogues()
    {
        // Existing paid jobs and deliberately cancelled refreshes are never auto-started.
        foreach (var p in Provider.BuiltIn.Where(p => Store.Configured(p.Id)))
        {
            var latest = Jobs.Snapshot().FirstOrDefault(j => j.Type == "catalogue" && j.ModelKey == p.Id && j.State != "Superseded");
            if (latest != null && latest.State != "Cancelled" && !latest.Remote.Flag("catalogue_requires_account_action") && (latest.State == "Waiting for provider" || !latest.Terminal || latest.Error.Contains("rate or concurrency limit", StringComparison.OrdinalIgnoreCase))) StartCatalogue(p, latest.Id);
        }
    }
}
