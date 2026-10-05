from pathlib import Path
root=Path('ronin-vanta-windows')
def edit(name,old,new,count=1):
    p=root/name;t=p.read_text(encoding='utf-8')
    if t.count(old)!=count: raise RuntimeError(f'{name}: expected {count} anchors, got {t.count(old)}: {old[:80]}')
    p.write_text(t.replace(old,new),encoding='utf-8')
for p in [root/'src/Vanta.Windows/MainWindow.cs',root/'tests/Vanta.Tests/CatalogueAcceptanceTests.cs']:
    p.write_text(p.read_text(encoding='utf-8').replace('0.4.2','0.4.3'),encoding='utf-8')
edit('src/Vanta.Core/ForgeExecution.cs','''        if (complete != null && complete.Str("input_fingerprint") == fingerprint)
            return complete["project"]!.Deserialize<ProjectRecord>(JsonEx.Options)!;''','''        if (complete != null && complete.Str("input_fingerprint") == fingerprint) {
            var retained = complete["project"]!.Deserialize<ProjectRecord>(JsonEx.Options)!;
            retained.ModelKey = model.Key; return retained;
        }''')
edit('src/Vanta.Core/ForgeExecution.cs','string path = ProjectFiles.Normalize(entry.Str("path"));','string path = ProjectFiles.Normalize(entry.Str("path"));\n                if (entry is JsonObject item) item["path"] = path;')
edit('src/Vanta.Core/ForgeExecution.cs','    public void ChangeForgeModel(string id, ModelRecord selected)','''    public void ChangeForgeModel(string id, ModelRecord selected) => ConfigureForgeModel(id, selected, true);
    public void ConfigureForgeModel(string id, ModelRecord selected, bool resume = false)''')
edit('src/Vanta.Core/ForgeExecution.cs','Models.Remember(selected); Resume(id);','Models.Remember(selected); if (resume) Resume(id);')
edit('src/Vanta.Core/ForgeReliability.cs','long lastText = 0; int longest = 0;','long lastText = 0; int longest = 0; int closed = 0;')
edit('src/Vanta.Core/ForgeReliability.cs','''        var request = send(text =>
        {
            if (text.Length''','''        var request = send(text =>
        {
            if (Volatile.Read(ref closed) != 0) return;
            if (text.Length''')
edit('src/Vanta.Core/ForgeReliability.cs','finally { stop.Cancel(); }','finally { Interlocked.Exchange(ref closed, 1); stop.Cancel(); }')
edit('src/Vanta.Core/RemoteBuilder.cs','''        string path = "vanta-forge/requests/" + requestId + ".json";
        string sha =''','''        string legacyId = job.Id + "-" + attempt;
        if (remote.Str("request_id") == legacyId && remote.Str("source_fingerprint").Length == 0) {
            // Upgrade recovery validates the old upload before adopting its saved commit.
            var entry = await api.JsonAsync(Github, HttpMethod.Get, root + "/contents/vanta-forge/requests/" + legacyId + ".json?ref=" + Uri.EscapeDataString(p.Branch), null, job.Token);
            var request = JsonNode.Parse(Convert.FromBase64String(entry.Str("content").Replace("\\n", ""))) as JsonObject ?? throw new ForgeFailure("Build worker", "The retained legacy submission is unreadable.");
            var prior = ProjectFiles.Parse(request.Obj("project").ToJsonString(), "Android");
            if (request.Str("request_id") == legacyId && ProjectFiles.Fingerprint(prior) == fingerprint) {
                requestId = legacyId; remote["source_fingerprint"] = fingerprint; job.Remote(remote);
            }
        }
        string path = "vanta-forge/requests/" + requestId + ".json";
        string sha =''')
p=root/'src/Vanta.Core/RemoteBuilder.cs';text=p.read_text();a=text.index('        var (p, root) = Configuration(); var info');b=text.index('\n    }',a)
text=text[:a]+'''        try {
'''+text[a:b]+'''
        } catch (ProviderHttpException error) { throw new ForgeFailure("Build worker", "HTTP " + error.Status + ": " + error.Message, "Check the private worker connection. This lookup did not submit source or buy an AI generation.", error); }
'''+text[b:];p.write_text(text,encoding='utf-8')
edit('src/Vanta.Windows/ForgePage.cs','m => { manual = m.Key; ModelMode.SelectedItem = "Select model"; SaveMode(); }, ""','m => { BindRepairModel(m); manual = m.Key; ModelMode.SelectedItem = "Select model"; SaveMode(); SaveDraft(); }, ""')
edit('src/Vanta.Windows/ForgePage.cs','        imported = p; ProjectMode.SelectedItem = "Existing project";','''        try { p.ModelKey = Services.Models.Select("forge", ModelMode.SelectedItem?.ToString() ?? "Default", manual, p.Name, Services.Preferences.DefaultFallback).Key; } catch (VantaException) { /* Source import and compiler preflight do not require an AI account. */ }
        imported = p; ProjectMode.SelectedItem = "Existing project";''')
edit('src/Vanta.Windows/ForgePage.cs','if (saved != null) { imported = current = saved; ProjectMode.SelectedItem = "Existing project"; ShowProject(saved, true); }','''if (saved != null) { imported = current = saved; ProjectMode.SelectedItem = "Existing project";
                if (saved.ModelKey.Length > 0) { manual = saved.ModelKey; ModelMode.SelectedItem = "Select model"; SaveMode(); }
                job = Services.Jobs.Snapshot().FirstOrDefault(j => j.ProjectId == saved.Id)?.Id ?? "";
                ShowProject(saved, true); }''')
edit('src/Vanta.Windows/ForgePage.cs','    private void SaveDraft() =>','''    private void BindRepairModel(ModelRecord selected)
    {
        if (ProjectMode.SelectedItem?.ToString() != "Existing project" || imported == null) return;
        if (job.Length > 0 && Services.Jobs.Get(job) is JobRecord task) {
            if (!task.Terminal) throw new VantaException("Stop the active task before changing its repair model.");
            if (task.Type == "forge" && task.State != "Complete") Services.ConfigureForgeModel(job, selected);
        }
        imported.ModelKey = selected.Key; if (current != null) current.ModelKey = selected.Key;
        Services.Store.Save("project", imported.Id, imported);
    }
    private void SaveDraft() =>''')
edit('tests/Vanta.Tests/ForgeReliabilityTests.cs','var shell = new MainWindow(service) { TestLifetime = true }; shell.Show();','var shell = new MainWindow(service) { TestLifetime = true }; Application.Current.MainWindow = shell; shell.Show();')
edit('tests/Vanta.Tests/Program.cs','''        Evidence = Path.GetFullPath(args.Length == 0 ? "qa-evidence" : args[0]);''','''        if (args.Length > 0 && args[0] == "--verify-upgrade") return VerifyUpgrade(args[1], args[2]);
        Evidence = Path.GetFullPath(args.Length == 0 ? "qa-evidence" : args[0]);''')
(root/'tests/Vanta.Tests/UpgradeVerification.cs').write_text(r'''using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Vanta.Core;
namespace Vanta.Tests;
public static partial class Program
{
    static int VerifyUpgrade(string root, string evidence)
    {
        try {
            using var s = new Store(root);
            if (s.Key("featherless") != "upgrade-fixture-not-a-live-key") throw new Exception("Fixture credential lost");
            if (s.Key("github") != "upgrade-worker-fixture") throw new Exception("Fixture worker credential lost");
            var c = s.All<Conversation>("conversation").Single(c => c.Title == "Upgrade fixture");
            if (c.Draft != "Retain this Windows draft" || c.Messages.Single().Text != "Fictional upgrade fixture") throw new Exception("Conversation or draft changed");
            var p = s.All<ProjectRecord>("project").Single();
            if (p.ModelKey != "featherless|qa-upgrade-model") throw new Exception("Repair model changed");
            var job = s.All<JobRecord>("job").Single();
            if (job.State != "Action required" || job.ModelKey != p.ModelKey) throw new Exception("Failed task not retained");
            if (s.Read<JsonObject>("job-step",job.Id+":author-repair-1-file-fixture").Str("text") != "completed fixture source") throw new Exception("Partial file checkpoint lost");
            if (s.Read<string>("job-partial",job.Id) != "unfinished answer fixture") throw new Exception("Partial response lost");
            if (s.Read<JsonObject>("job-step",job.Id+":source").Str("Id") != p.Id) throw new Exception("Task source identity lost");
            if (s.Read<BuildPreferences>("settings","build")?.Branch != "fixture-branch") throw new Exception("Worker settings lost");
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(evidence))!);
            File.WriteAllText(evidence,JsonSerializer.Serialize(new { status="PASS", baseline="Installed Windows 0.4.2", updated="Installed Windows 0.4.3", credential="retained and decrypted", worker="retained", conversation="retained", draft="retained", project="retained", repairModel="retained", failedTask="retained", fileCheckpoint="retained", partialResponse="retained", scope="Fictional fixture, same Windows account; no live provider call" },new JsonSerializerOptions{WriteIndented=true}));
            Console.WriteLine("PASS in-place 0.4.2 -> 0.4.3 encrypted state verification"); return 0;
        } catch(Exception e) { Console.Error.WriteLine(e); return 1; }
    }
}
''',encoding='utf-8')
print('Finished project-bound model selection, late-stream isolation, legacy submission recovery, worker errors and upgrade checks.')
