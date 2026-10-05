from pathlib import Path
import shutil
root=Path('ronin-vanta-windows'); here=Path(__file__).parent

def edit(path,old,new,count=1):
    p=root/path; text=p.read_text(encoding='utf-8')
    if text.count(old)!=count:raise RuntimeError(f'{path}: expected {count} matches, got {text.count(old)}: {old[:100]}')
    p.write_text(text.replace(old,new),encoding='utf-8')

edit('src/Vanta.Core/ProviderApi.cs','public sealed class ProviderApi : IDisposable','public sealed partial class ProviderApi : IDisposable')
edit('src/Vanta.Core/ProviderApi.cs','JsonObject? body = null)\n    {\n        var uri = Https(url);','JsonObject? body = null, bool includeCredentials = true)\n    {\n        var uri = Https(url);')
edit('src/Vanta.Core/ProviderApi.cs','var key = keys(provider.Id);','var key = includeCredentials ? keys(provider.Id) : null;')
edit('src/Vanta.Core/ProviderApi.cs','if (provider.Id == "anthropic") request.Headers.Add("anthropic-version", "2023-06-01");','''if (provider.Id == "anthropic") request.Headers.Add("anthropic-version", "2023-06-01");
            if (provider.Id == "featherless") { request.Headers.TryAddWithoutValidation("X-Title", "Ronin Vanta"); request.Headers.TryAddWithoutValidation("HTTP-Referer", "https://github.com/RoninMerc/ronin-os"); }''')
edit('src/Vanta.Core/ProviderApi.cs','429 => "The provider\'s rate or concurrency limit was reached."','429 => readOnly ? "The provider rate-limited this read-only metadata lookup (HTTP 429)." : "The provider\'s rate or concurrency limit was reached."')
edit('src/Vanta.Core/Models.cs','?status=active,pending_deploy,not_deployed&per_page=1000&page=1','?status=active,pending_deploy,not_deployed')
edit('src/Vanta.Core/Models.cs','''var meta = root["pagination"] as JsonObject ?? root["meta"] as JsonObject ?? root;
            long current''','''// A data+total response is a bulk snapshot, not a cursor. Never infer extra pages from total alone.
            bool explicitPaging = root["pagination"] is JsonObject || root["meta"] is JsonObject metadata && (metadata["current_page"] != null || metadata["per_page"] != null || metadata["total_pages"] != null || metadata["last_page"] != null) || root["current_page"] != null || root["total_pages"] != null || root["has_more"] != null;
            if (!explicitPaging) return null;
            var meta = root["pagination"] as JsonObject ?? root["meta"] as JsonObject ?? root;
            long current''')
edit('src/Vanta.Core/Models.cs','if (!more) return null;\n            if (rows.Count == 0 || current != page)','if (current != page) throw new VantaException("The catalogue repeated or skipped a page.");\n            if (!more) return null;\n            if (rows.Count == 0 || current != page)')
edit('src/Vanta.Core/Models.cs','&per_page=1000&page={page + 1}','&per_page={Math.Clamp(size, 1, 1000)}&page={page + 1}')
edit('src/Vanta.Core/Models.cs','store.Read<List<ModelRecord>>("catalogue", p.Id, false)','CatalogueStorage.Read(store, "catalogue", p.Id, false)')
edit('src/Vanta.Core/Models.cs','public void Commit(Provider p, List<ModelRecord> incoming)','public void Commit(Provider p, List<ModelRecord> incoming, bool verifiedComplete = true)')
edit('src/Vanta.Core/Models.cs','foreach (var removed in old.Values) { removed.Status = "removed"; incoming.Add(removed); }','''foreach (var previous in old.Values) { var retained = JsonEx.Clone(previous); if (verifiedComplete) retained.Status = "removed"; else retained.EndpointNote += "\nNot present in the latest count-discrepant snapshot; previous metadata retained, not a new verification."; incoming.Add(retained); }'''.replace('"\nNot present','"\\nNot present'))
edit('src/Vanta.Core/Models.cs','store.Save("catalogue", p.Id, incoming, false);','CatalogueStorage.Write(store, "catalogue", p.Id, incoming, false);')
edit('src/Vanta.Core/Models.cs','''int index = rows.FindIndex(x => x.Key == model.Key); if (index >= 0) rows[index] = updated;
            store.Save("catalogue", model.ProviderId, rows.Where(x => x.ProviderId == model.ProviderId).ToList(), false);''','''int index = rows.FindIndex(x => x.Key == model.Key);
            var replacement = rows.Where(x => x.ProviderId == model.ProviderId).Select(x => x.Key == model.Key ? updated : x).ToList();
            CatalogueStorage.Write(store, "catalogue", model.ProviderId, replacement, false);
            if (index >= 0) rows[index] = updated;''')
edit('src/Vanta.Core/Models.cs','m.RefreshSearch(); return m;','''if (provider.Id == "featherless" && raw.Str("status").Length == 0) m.EndpointNote += "\\nThe public list does not supply deployment status or verify your account entitlement. Refresh this model's details for deployment status; inference still uses your saved API key.";
        m.RefreshSearch(); return m;''')
edit('src/Vanta.Core/CatalogueSync.cs','public string Identity { get; set; } = "";','public string Identity { get; set; } = "";\n    public string Protocol { get; set; } = "";\n    public long DeclaredCount { get; set; } = -1;\n    public bool CountDiscrepancy { get; set; }\n    public string LastFailure { get; set; } = "";')
edit('src/Vanta.Core/CatalogueSync.cs','int Attempts = 0);','int Attempts = 0, JsonObject? Diagnostic = null);')
edit('src/Vanta.Core/CatalogueSync.cs','foreach (var id in store.Ids("catalogue-page").Where(id => id.StartsWith(p.Id + ":", StringComparison.Ordinal))) store.Delete("catalogue-page", id);','foreach (var id in store.Ids("catalogue-page").Concat(store.Ids("catalogue-page-manifest")).Distinct().Where(id => id.StartsWith(p.Id + ":", StringComparison.Ordinal))) CatalogueStorage.Delete(store, "catalogue-page", id);')
edit('src/Vanta.Core/CatalogueSync.cs','new CatalogueCheckpoint { Identity = Identity(store, p), Url','new CatalogueCheckpoint { Identity = Identity(store, p), Protocol = "bulk-public-042", Url')
edit('src/Vanta.Core/CatalogueSync.cs','else if (options.Now() - checkpoint.Started > TimeSpan.FromHours(6))','else if (p.Id == "featherless" && checkpoint.Protocol != "bulk-public-042") checkpoint = Fresh(p, checkpoint.NextAttempt);\n            else if (options.Now() - checkpoint.Started > TimeSpan.FromHours(6))')
edit('src/Vanta.Core/CatalogueSync.cs','store.Read<List<ModelRecord>>("catalogue-page", PageKey(p, checkpoint, page))','CatalogueStorage.Read(store, "catalogue-page", PageKey(p, checkpoint, page))')
edit('src/Vanta.Core/CatalogueSync.cs','No generation is being submitted.", checkpoint.Page, incoming.Count, due, checkpoint.Failures)','No generation is being submitted. {checkpoint.LastFailure}", checkpoint.Page, incoming.Count, due, checkpoint.Failures, api.CatalogueDiagnostic(p.Id))')
edit('src/Vanta.Core/CatalogueSync.cs','await api.JsonAsync(p, HttpMethod.Get, checkpoint.Url!, null, ct)','await api.CatalogueJsonAsync(p, checkpoint.Url!, ct)')
edit('src/Vanta.Core/CatalogueSync.cs','checkpoint.Failures = Math.Min(100000, checkpoint.Failures + 1);','checkpoint.LastFailure = UserErrors.Clean(error.Message);\n                    checkpoint.Failures = Math.Min(100000, checkpoint.Failures + 1);')
edit('src/Vanta.Core/CatalogueSync.cs','var pageRows = new List<ModelRecord>(); int novel = 0;','var pageRows = new List<ModelRecord>(); int novel = 0;\n                if (items.Count == 0 && p.Id == "featherless") throw new VantaException("Featherless returned an empty model catalogue.", "The previous cache has not been replaced.");')
edit('src/Vanta.Core/CatalogueSync.cs','if (next != null && novel == 0)','if ((next != null || checkpoint.Page > 1) && novel == 0)')
edit('src/Vanta.Core/CatalogueSync.cs','if (declared >= 0 && declared != incoming.Count) throw new VantaException("The provider returned an incomplete catalogue.", "The previous complete cache has been retained; refresh later.");','''checkpoint.DeclaredCount = declared;
                    checkpoint.CountDiscrepancy = declared >= 0 && declared != incoming.Count;
                    if (checkpoint.CountDiscrepancy && p.Id != "featherless") throw new VantaException("The provider returned an incomplete catalogue.", "The previous complete cache has been retained; refresh later.");''')
edit('src/Vanta.Core/CatalogueSync.cs','store.Save("catalogue-page", PageKey(p, checkpoint, checkpoint.Page), pageRows);','CatalogueStorage.Write(store, "catalogue-page", PageKey(p, checkpoint, checkpoint.Page), pageRows);')
edit('src/Vanta.Core/CatalogueSync.cs','registry.Commit(p, incoming.Values.ToList()); registry.ApplyPreferences();','''int received = incoming.Count;
            progress?.Invoke(new("Saving catalogue", $"Saving {received:N0} returned models in bounded local records.", checkpoint.Page, received, Diagnostic: api.CatalogueDiagnostic(p.Id)));
            registry.Commit(p, incoming.Values.ToList(), !checkpoint.CountDiscrepancy); registry.ApplyPreferences();
            string warning = checkpoint.CountDiscrepancy ? $"Provider count discrepancy: {received:N0} unique records received; {checkpoint.DeclaredCount:N0} reported. Every returned model was saved; previously cached models were not marked removed merely because of this mismatch. The reported total is not a verified number of runnable models." : "";
            store.Save("catalogue-observation", p.Id, new JsonObject { ["received"] = received, ["reported"] = checkpoint.DeclaredCount, ["count_discrepancy"] = checkpoint.CountDiscrepancy, ["note"] = warning, ["transport"] = api.CatalogueDiagnostic(p.Id) });''')
edit('src/Vanta.Core/CatalogueSync.cs','["at"] = options.Now().ToString("O")','["at"] = options.Now().ToString("O"), ["protocol"] = "bulk-public-042"')
edit('src/Vanta.Core/CatalogueSync.cs','if (success.Str("identity") == CatalogueReader.Identity(Store, provider)','if ((provider.Id != "featherless" || success.Str("protocol") == "bulk-public-042") && success.Str("identity") == CatalogueReader.Identity(Store, provider)')
edit('src/Vanta.Core/CatalogueSync.cs','j.Remote["catalogue_retry"] = report.Attempts;','j.Remote["catalogue_retry"] = report.Attempts;\n                        if (report.Diagnostic != null) j.Remote["catalogue_http"] = report.Diagnostic.Copy();')
edit('src/Vanta.Core/CatalogueSync.cs','''c.Complete(new() { ["text"] = $"{Models.Snapshot().Count(m => m.ProviderId == provider.Id):N0} catalogue entries saved. Account permissions and endpoint compatibility still apply." });''','''var observation = Store.Read<JsonObject>("catalogue-observation", provider.Id);
                c.Complete(new() { ["text"] = $"{Models.Snapshot().Count(m => m.ProviderId == provider.Id):N0} catalogue entries saved. Account permissions and endpoint compatibility still apply.\\n\\n" + observation.Str("note") });''')
edit('src/Vanta.Core/CatalogueSync.cs','latest.Error.Contains("rate or concurrency limit", StringComparison.OrdinalIgnoreCase)','(latest.Error.Contains("rate or concurrency limit", StringComparison.OrdinalIgnoreCase) || p.Id == "featherless" && (latest.Error.Contains("incomplete catalogue", StringComparison.OrdinalIgnoreCase) || latest.Error.Contains("saved record is too large", StringComparison.OrdinalIgnoreCase)))')
edit('src/Vanta.Windows/ActivityPage.cs','"\\nCatalogue retry due: "','"\\nCatalogue HTTP: " + j.Remote.Obj("catalogue_http").ToJsonString() + "\\nCatalogue retry due: "')
edit('src/Vanta.Windows/SettingsPage.cs','? "Connected" : "Not connected"','? "API key saved (not a live verification)" : "No API key saved"')
edit('src/Vanta.Windows/SettingsPage.cs','Shell.Notice(p.Name + " connected. Syncing available models…");','Shell.Notice(p.Name + " key saved. Loading the catalogue; this does not verify account access.");')
edit('src/Vanta.Windows/SettingsPage.cs','card.Children.Add(Ui.Text(p.BaseUrl, 11,"Muted"));','''if (p.Id == "featherless") card.Children.Add(Ui.Row(Ui.Async("Check saved key", async () => { status.Text = "Checking the saved key with Featherless /v1/plan…"; try { await Services.Api.CheckFeatherlessKeyAsync(CancellationToken.None); status.Text = "Saved key accepted by Featherless /v1/plan. No generation submitted. Model-specific access may differ."; } catch { status.Text = "The key check did not succeed. See the error details; no generation was submitted."; throw; } }), Ui.Button("Export catalogue diagnostics", () => { var report = Services.Store.Read<JsonObject>("catalogue-observation", p.Id) ?? new(); report["latest_request"] = Services.Api.CatalogueDiagnostic(p.Id); report["app_version"] = "0.4.2"; Ui.SaveText("Vanta-Featherless-catalogue-diagnostics.json", report.ToJsonString(new JsonSerializerOptions { WriteIndented = true })); })));
            card.Children.Add(Ui.Text(p.BaseUrl, 11,"Muted"));''')
edit('tests/Vanta.Tests/CoreTests.cs','"Failed catalogue refresh preserves the complete previous cache"','"Malformed catalogue refresh preserves the complete previous cache"')
edit('tests/Vanta.Tests/CoreTests.cs','new JsonObject { ["id"] = "new" }), ["pagination"]','new JsonObject { ["id"] = "" }), ["pagination"]')
edit('tests/Vanta.Tests/Program.cs','providerCalls = "isolated HTTP fixtures, not live paid accounts"','providerCalls = Environment.GetEnvironmentVariable("VANTA_LIVE_CATALOGUE_TEST") == "1" ? "Isolated HTTP regressions plus one live public Featherless catalogue GET; no live inference/account credentials" : "isolated HTTP fixtures, not live paid accounts"')
for p in [*root.rglob('*.cs'),root/'Directory.Build.props',root/'installer.iss',root/'src/Vanta.Windows/app.manifest']:
    text=p.read_text(encoding='utf-8');p.write_text(text.replace('0.4.1','0.4.2'),encoding='utf-8')
edit('tests/Vanta.Tests/CatalogueTests.cs','True(url.Contains("page=1"));','True(!url.Contains("page=") && url.Contains("status=active,pending_deploy,not_deployed"));')
edit('tests/Vanta.Tests/Program.cs','await CatalogueAcceptanceTests();','await CatalogueAcceptanceTests(); await FeatherlessContractTests();')
for name,target in [('CatalogueStorage.cs','src/Vanta.Core'),('FeatherlessTransport.cs','src/Vanta.Core'),('FeatherlessContractTests.cs','tests/Vanta.Tests')]:shutil.copyfile(here/name,root/target/name)
package=Path('vanta/windows-041/package.ps1').read_text(encoding='utf-8').replace('0.4.1','0.4.2')
(here/'package.ps1').write_text(package,encoding='utf-8')
print('Applied 0.4.2: bulk public discovery, explicit pagination only, visible count reconciliation, bounded atomic catalogue storage, HTTP diagnostics and independent key check.')
