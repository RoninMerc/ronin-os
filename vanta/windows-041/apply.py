from pathlib import Path
import hashlib
import shutil

root = Path('ronin-vanta-windows')
source = Path(__file__).parent
expected = {
 'Directory.Build.props': 'b3ccc83459caceb59ca68fb7665a2c80f33f97c2f81ddd1222253f3c0d1d4da9',
 'installer.iss': '0e00ba0914b8b4044074f2b3e969ddc1d3f40cb38187e7dd94b3a09cb2833389',
 'src/Vanta.Core/Jobs.cs': '195210673a5ecb852571ce6fe48f4ffed4016dddf6d3dd7be56e186a492aef9f',
 'src/Vanta.Core/Models.cs': '02c42d121be09bc481a0a0921d1641405ef7db050cd28673c90e145e6966da2e',
 'src/Vanta.Core/ProviderApi.cs': '2fabbdc5ea16987e37fa3d323c7ac19a45ecea8d953bce0354852a617480a309',
 'src/Vanta.Core/Workflows.cs': '6263eb7bb2292b3974c61a66a10b317a9fa73097f000a805494f3c6c04ff2fe0',
 'src/Vanta.Windows/ActivityPage.cs': '5536bd0e6596785f0376c4774af6e10a6dbb4b83ddeb861d2f5061b65aba6fff',
 'tests/Vanta.Tests/Program.cs': '597ee43bea423684f7356048e7ac7eca248e32bdcf2d8b97c79a2e52db9205b3'
}
for name, digest in expected.items():
    actual = hashlib.sha256((root/name).read_text(encoding='utf-8').encode()).hexdigest()
    if actual != digest:
        raise RuntimeError(f'Unexpected 0.4.0 baseline: {name}: {actual} != {digest}')

def edit(path, old, new, count=1):
    p = root/path
    text = p.read_text(encoding='utf-8')
    if text.count(old) != count:
        raise ValueError(f'{path}: replacement count mismatch: {old[:100]}')
    p.write_text(text.replace(old, new), encoding='utf-8')

edit('src/Vanta.Core/Workflows.cs','public sealed class VantaServices','public sealed partial class VantaServices')
edit('src/Vanta.Core/Workflows.cs','public VantaServices(string root, HttpMessageHandler? handler = null)','public VantaServices(string root, HttpMessageHandler? handler = null, CatalogueSyncOptions? catalogueOptions = null)')
edit('src/Vanta.Core/Workflows.cs','Models = new(Store); Models.ApplyPreferences(); Api = new(Store.Key, handler); Jobs = new(Store);','Models = new(Store, catalogueOptions); Models.ApplyPreferences(); Api = new(Store.Key, handler); Jobs = new(Store); RecoverCatalogues();')
p=root/'src/Vanta.Core/Workflows.cs'
t=p.read_text(encoding='utf-8'); a=t.index('    public JobRecord Refresh('); b=t.index('    public JobRecord Chat(',a)
p.write_text(t[:a]+'    public JobRecord Refresh(Provider provider) => StartCatalogue(provider);\n'+t[b:],encoding='utf-8')
edit('src/Vanta.Core/Workflows.cs','if (job.Type == "catalogue") { Jobs.Start(id, async c => { var p = Provider.Get(c.Input.Str("provider")); await Models.RefreshAsync(p, Api, s => c.Stage("Discovering models", s), c.Token); c.Complete(new() { ["text"] = "Catalogue refreshed." }); }); return; }','if (job.Type == "catalogue") { StartCatalogue(Provider.Get(job.ModelKey), id); return; }')
edit('src/Vanta.Core/Models.cs','public ModelRegistry(Store store) { this.store = store; Reload(); }','private readonly CatalogueReader catalogueReader;\n    public ModelRegistry(Store store, CatalogueSyncOptions? options = null) { this.store = store; catalogueReader = new(store, options ?? new()); Reload(); }')
p=root/'src/Vanta.Core/Models.cs'; t=p.read_text(encoding='utf-8'); a=t.index('    public async Task RefreshAsync('); b=t.index('    public async Task<ModelRecord> DetailAsync',a)
p.write_text(t[:a]+'''    public Task RefreshAsync(Provider p, ProviderApi api, Action<string>? status, CancellationToken ct, Action<CatalogueProgress>? details = null)
        => catalogueReader.RefreshAsync(this, p, api, update => { status?.Invoke(update.Action); details?.Invoke(update); }, ct);
'''+t[b:],encoding='utf-8')
edit('src/Vanta.Core/ProviderApi.cs','public sealed class ProviderHttpException(int status, string message, string recovery) : VantaException(message, recovery) { public int Status { get; } = status; }','''public sealed class ProviderHttpException(int status, string message, string recovery, DateTimeOffset? retryNotBefore = null, string errorCode = "") : VantaException(message, recovery)
{
    public int Status { get; } = status;
    public DateTimeOffset? RetryNotBefore { get; } = retryNotBefore;
    public string ErrorCode { get; } = errorCode;
    public bool AccountActionRequired => ErrorCode.ToLowerInvariant() is "insufficient_quota" or "billing_hard_limit_reached" or "credit_balance_exhausted" or "insufficient_credits" or "billing_not_active";
}''')
edit('src/Vanta.Core/ProviderApi.cs','RoninVantaWindows/0.3.0','RoninVantaWindows/0.4.1')
edit('src/Vanta.Core/ProviderApi.cs','private async Task CheckAsync(HttpResponseMessage response, CancellationToken ct)','private async Task CheckAsync(HttpResponseMessage response, CancellationToken ct, bool readOnly = false)')
edit('src/Vanta.Core/ProviderApi.cs','''string detail = ""; try { var bytes = await ReadBytesAsync(response.Content, 64 * 1024, null, ct); var node = JsonNode.Parse(bytes); detail = node.Obj("error").Str("message", node.Str("error", node.Str("message"))); }''','''string detail = "", code = "";
        DateTimeOffset? retry = null;
        if (response.Headers.TryGetValues("Retry-After", out var hints)) foreach (var hint in hints)
        {
            if (!RetryConditionHeaderValue.TryParse(hint, out var parsed)) continue;
            var now = DateTimeOffset.UtcNow;
            DateTimeOffset? candidate = parsed.Delta is TimeSpan delta ? now.AddSeconds(Math.Min(delta.TotalSeconds, (DateTimeOffset.MaxValue - now).TotalSeconds - 1)) : parsed.Date;
            if (candidate is DateTimeOffset date && (retry == null || date > retry)) retry = date;
        }
        try { var bytes = await ReadBytesAsync(response.Content, 64 * 1024, null, ct); var node = JsonNode.Parse(bytes); detail = node.Obj("error").Str("message", node.Str("error", node.Str("message"))); code = node.Obj("error").Str("code", node.Str("code", node.Obj("error").Str("type"))); }''')
edit('src/Vanta.Core/ProviderApi.cs','"No generation was automatically repeated.");','readOnly ? "This was a read-only lookup. The previous complete catalogue has not been replaced." : "No generation was automatically repeated.", retry, code);')
edit('src/Vanta.Core/ProviderApi.cs','''using var limit = Limit(ct, method == HttpMethod.Get ? 90 : 600); using var request = Request(provider, method, url, body);
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, limit.Token); await CheckAsync(response, limit.Token);''','''using var limit = Limit(ct, method == HttpMethod.Get ? 90 : 600); using var request = Request(provider, method, url, body);
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, limit.Token); await CheckAsync(response, limit.Token, method == HttpMethod.Get);''')
edit('src/Vanta.Core/Jobs.cs','State is "Complete" or "Failed" or "Cancelled" or "Action required" or "Blocked"','State is "Complete" or "Failed" or "Cancelled" or "Action required" or "Blocked" or "Superseded"')
edit('src/Vanta.Core/Jobs.cs','private readonly SemaphoreSlim concurrency = new(2, 2);','private readonly SemaphoreSlim concurrency = new(2, 2); private readonly SemaphoreSlim catalogueConcurrency = new(4, 4); private volatile bool stopping;')
edit('src/Vanta.Core/Jobs.cs','''if (store.Read<JsonObject>("job-result", j.Id) is JsonObject result)''','''if (j.Type == "catalogue") { j.State = "Waiting for provider"; j.Error = ""; j.RequestInFlight = false; j.Progress = ProgressValue.Unknown("Waiting for provider", "Read-only catalogue discovery will resume from its saved page and cooldown."); }
            else if (store.Read<JsonObject>("job-result", j.Id) is JsonObject result)''')
edit('src/Vanta.Core/Jobs.cs','public int Running => executions.Count;','public int Running => executions.Count;\n    public bool IsExecuting(string id) => cancellation.ContainsKey(id);')
edit('src/Vanta.Core/Jobs.cs','''if (cancellation.ContainsKey(id)) throw new VantaException("This task is already running.");''','''if (stopping) throw new VantaException("Vanta is closing. Reopen it before starting work.");
        if (cancellation.ContainsKey(id)) return;''')
edit('src/Vanta.Core/Jobs.cs','''Update(id, j => { j.State = "Queued"; j.Error = ""; });
        var task = Task.Run''','''Update(id, j => { j.State = "Queued"; j.Error = ""; if (j.Type == "catalogue") j.Remote["catalogue_user_cancelled"] = false; });
        var slots = Get(id)!.Type == "catalogue" ? catalogueConcurrency : concurrency;
        var task = Task.Run''')
edit('src/Vanta.Core/Jobs.cs','await concurrency.WaitAsync(cts.Token); entered = true;','await slots.WaitAsync(cts.Token); entered = true;')
edit('src/Vanta.Core/Jobs.cs','if (entered) concurrency.Release();','if (entered) slots.Release();')
edit('src/Vanta.Core/Jobs.cs','''catch (OperationCanceledException) { Update(id, j => { j.State = "Cancelled"; j.Error = "Stopped locally. Already submitted remote work may continue and be charged by the provider."; j.Progress = j.Progress with { Stage = "Cancelled", Action = j.Error }; }); }''','''catch (OperationCanceledException) { Update(id, j => {
                if (j.Type == "catalogue") { j.State = stopping && !j.Remote.Flag("catalogue_user_cancelled") ? "Waiting for provider" : "Cancelled"; j.Error = ""; j.Progress = j.Progress with { Stage = j.State, Action = j.State == "Cancelled" ? "Catalogue refresh stopped. Saved pages and the last complete cache are retained." : "Catalogue refresh saved for the next launch; the provider cooldown is retained." }; }
                else { j.State = "Cancelled"; j.Error = "Stopped locally. Already submitted remote work may continue and be charged by the provider."; j.Progress = j.Progress with { Stage = "Cancelled", Action = j.Error }; }
            }); }''')
edit('src/Vanta.Core/Jobs.cs','''if (Get(id) is { State: "Complete" }) return;
        if (cancellation.TryGetValue''','''if (Get(id) is { State: "Complete" }) return;
        if (Get(id)?.Type == "catalogue") Update(id, j => j.Remote["catalogue_user_cancelled"] = true);
        if (cancellation.TryGetValue''')
edit('src/Vanta.Core/Jobs.cs','public async Task StopAsync() { foreach','public async Task StopAsync() { stopping = true; foreach')
edit('src/Vanta.Core/Jobs.cs','public void Dispose() { foreach','public void Dispose() { stopping = true; foreach')
edit('src/Vanta.Core/Jobs.cs','j.Error = UserErrors.Explain(ex); j.Progress =','j.Error = UserErrors.Explain(ex); if (j.Type == "catalogue" && ex is ProviderHttpException http) j.Remote["catalogue_requires_account_action"] = http.AccountActionRequired || http.Status is 401 or 402 or 403; j.Progress =')
edit('src/Vanta.Windows/ActivityPage.cs','up to two active requests','up to two generation jobs; catalogue reads use separate paced slots')
edit('src/Vanta.Windows/ActivityPage.cs','j.State + " · " + (Services.Preferences.ShowPercent ? j.Progress.Label : j.Progress.Stage)','j.State == j.Progress.Stage && j.Progress.Percent == null ? j.State : j.State + " · " + (Services.Preferences.ShowPercent ? j.Progress.Label : j.Progress.Stage)')
edit('src/Vanta.Windows/ActivityPage.cs','Ui.Button("Resume saved work", () => { if (Ui.Confirm(','Ui.Button(j.Type == "catalogue" ? "Retry catalogue" : "Resume saved work", () => { if (j.Type == "catalogue" || Ui.Confirm(')
edit('src/Vanta.Windows/ActivityPage.cs','new Expander { Header = "Recent activity",','new Expander { Foreground = Ui.Brush("Text"), Header = "Recent activity",')
edit('src/Vanta.Windows/ActivityPage.cs','new Expander { Header = "Technical details",','new Expander { Foreground = Ui.Brush("Text"), Header = "Technical details",')
edit('src/Vanta.Windows/ActivityPage.cs','"\\nRemote identifier: " + j.Remote.Str','"\\nCatalogue retry due: " + j.Remote.Str("next_catalogue_read") + "\\nCatalogue page: " + j.Remote.Num("catalogue_page") + "\\nRemote identifier: " + j.Remote.Str')
edit('Directory.Build.props','<Version>0.4.0</Version>','<Version>0.4.1</Version>')
for old,new in [('AppVersion=0.4.0','AppVersion=0.4.1'),('AppVerName=Ronin Vanta Windows 0.3.0','AppVerName=Ronin Vanta Windows 0.4.1'),('Ronin-Vanta-Windows-0.4.0-Setup','Ronin-Vanta-Windows-0.4.1-Setup'),('VersionInfoVersion=0.3.0.0','VersionInfoVersion=0.4.1.0')]:
    edit('installer.iss',old,new)
edit('tests/Vanta.Tests/Program.cs','await CoreTests(); await WorkflowTests(); await DesktopTests();','await CoreTests(); await CatalogueTests(); await WorkflowTests(); await DesktopTests();')
shutil.copyfile(source/'CatalogueSync.cs',root/'src/Vanta.Core/CatalogueSync.cs')
shutil.copyfile(source/'CatalogueTests.cs',root/'tests/Vanta.Tests/CatalogueTests.cs')
package=Path('vanta/windows-040/package.ps1').read_text(encoding='utf-8').replace('0.4.0','0.4.1').replace('Vanta 0.3 installer','Vanta 0.4.1 installer')
(source/'package.ps1').write_text(package,encoding='utf-8')
print('Applied Windows 0.4.1 catalogue recovery to the exact verified 0.4.0 source baseline.')
