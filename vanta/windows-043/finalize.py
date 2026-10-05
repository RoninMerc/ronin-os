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
edit('src/Vanta.Core/RemoteBuilder.cs','''        string path = "vanta-forge/requests/" + requestId + ".json";
        string sha =''','''        string legacyId = job.Id + "-" + attempt;
        if (remote.Str("request_id") == legacyId && remote.Str("source_fingerprint").Length == 0) {
            // Upgrade recovery: validate an old submission's bytes before adopting its commit.
            // Failure to read it stops here rather than buying a duplicate upload.
            var entry = await api.JsonAsync(Github, HttpMethod.Get, root + "/contents/vanta-forge/requests/" + legacyId + ".json?ref=" + Uri.EscapeDataString(p.Branch), null, job.Token);
            var request = JsonNode.Parse(Convert.FromBase64String(entry.Str("content").Replace("\\n", ""))) as JsonObject ?? throw new ForgeFailure("Build worker", "The retained legacy submission is unreadable.");
            var prior = ProjectFiles.Parse(request.Obj("project").ToJsonString(), "Android");
            if (request.Str("request_id") == legacyId && ProjectFiles.Fingerprint(prior) == fingerprint) {
                requestId = legacyId; remote["source_fingerprint"] = fingerprint; job.Remote(remote);
            }
        }
        string path = "vanta-forge/requests/" + requestId + ".json";
        string sha =''')
# A main-thread UI helper must not assume the destroyed test window is the application's owner.
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
            var c = s.All<Conversation>("conversation").Single();
            if (c.Draft != "Retain this Windows draft" || c.Messages.Single().Text != "Fictional upgrade fixture") throw new Exception("Conversation or draft changed");
            var p = s.All<ProjectRecord>("project").Single();
            if (p.ModelKey != "featherless|qa-upgrade-model") throw new Exception("Repair model changed");
            var job = s.All<JobRecord>("job").Single();
            if (job.State != "Action required" || job.ModelKey != p.ModelKey) throw new Exception("Failed task not retained");
            var checkpoint = s.Read<JsonObject>("job-step",job.Id+":author-repair-1-file-fixture");
            if (checkpoint.Str("text") != "completed fixture source") throw new Exception("Partial file checkpoint lost");
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
print('Finished model checkpoint identity, legacy worker recovery and upgrade verification hooks.')
