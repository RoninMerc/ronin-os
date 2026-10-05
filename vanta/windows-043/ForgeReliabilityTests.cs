using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Vanta.Core;
using Vanta.Windows;

namespace Vanta.Tests;
public static partial class Program
{
    static ProjectRecord AndroidFixture() => new() {
        Platform = "Android", Name = "Vanta gate verification fixture", ModelKey = Model().Key,
        Files = new() {
            new() { Path = "settings.gradle", Content = "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name='GateFixture'\ninclude ':app'\n" },
            new() { Path = "build.gradle", Content = "plugins { id 'com.android.application' version '8.7.3' apply false }\n" },
            new() { Path = "app/build.gradle", Content = "plugins { id 'com.android.application' }\nandroid { namespace 'au.com.ronin.gatefixture'; compileSdk 35\ndefaultConfig { applicationId 'au.com.ronin.gatefixture'; minSdk 23; targetSdk 35; versionCode 1; versionName '1.0' } }\ndependencies { testImplementation 'junit:junit:4.13.2' }\n" },
            new() { Path = "app/src/main/AndroidManifest.xml", Content = "<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"Gate Fixture\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>" },
            new() { Path = "app/src/main/java/au/com/ronin/gatefixture/MainActivity.java", Content = "package au.com.ronin.gatefixture; public class MainActivity extends android.app.Activity {}" },
            new() { Path = "app/src/test/java/au/com/ronin/gatefixture/GateTest.java", Content = "package au.com.ronin.gatefixture; import org.junit.Test; import static org.junit.Assert.*; public class GateTest { @Test public void arithmetic() { assertEquals(4, 2+2); } @Test public void marker() { assertTrue(true); } }" }
        }
    };
    static JobRecord SeedForge(VantaServices service, ProjectRecord project, BuildReport? failure = null)
    {
        var job = service.Jobs.Create("forge", "Retained fixture task", project.ModelKey,
            new JsonObject { ["project"] = JsonSerializer.SerializeToNode(project), ["routing_mode"] = "Select model", ["manual_model"] = project.ModelKey }, project: project.Id);
        service.Store.Save("project", project.Id, project);
        service.Store.Save("job-step", job.Id + ":source", project);
        if (failure != null) service.Store.Save("job-step", job.Id + ":compiler-1", new JsonObject { ["fingerprint"] = ProjectFiles.Fingerprint(project), ["report"] = JsonSerializer.SerializeToNode(failure) });
        service.Jobs.Update(job.Id, j => j.State = "Action required"); return job;
    }
    static ForgeRequestLimits TinyLimits() => new() { Idle = TimeSpan.FromMilliseconds(120), Overall = TimeSpan.FromSeconds(2), Tick = TimeSpan.FromMilliseconds(10) };

    static async Task ForgeReliabilityTests()
    {
        await Test("043: XML declaration prefix normalises narrowly and leaves original snapshot unchanged", () => {
            var p = AndroidFixture(); p.Files[3].Content = "\uFEFF\n \t" + p.Files[3].Content;
            var fixedProject = AndroidForgePreparation.Prepare(p, out var changes);
            True(p.Files[3].Content.StartsWith("\uFEFF")); True(fixedProject.Files[3].Content.StartsWith("<?xml")); True(changes.Any(c => c.Contains("XML")));
            Equal("  <resources/>", AndroidForgePreparation.NormaliseXml("  <resources/>"));
        });
        await Test("043: malformed XML fails before a remote/provider submission", () => {
            var p = AndroidFixture(); p.Files[3].Content = "<manifest>";
            Reject<ForgeFailure>(() => AndroidForgePreparation.Prepare(p, out _));
        });
        await Test("043: XML external entities are prohibited", () => {
            var p = AndroidFixture(); p.Files[3].Content = "<?xml version=\"1.0\"?><!DOCTYPE manifest [<!ENTITY x SYSTEM \"file:///private\">]><manifest>&x;</manifest>";
            Reject<ForgeFailure>(() => AndroidForgePreparation.Prepare(p, out _));
        });
        await Test("043: leading whitespace in the legacy quality marker is recovered", () => {
            var p = AndroidFixture(); p.Files.Add(new() { Path = AndroidForgePreparation.GatePath, Content = "\n \uFEFF" + AndroidForgePreparation.QualityGate });
            var repaired = AndroidForgePreparation.Prepare(p, out _);
            Equal(AndroidForgePreparation.QualityGate, repaired.Files.Single(f => f.Path == AndroidForgePreparation.GatePath).Content);
            True(repaired.ManagedBuildFiles.ContainsKey(AndroidForgePreparation.GatePath));
        });
        await Test("043: external ownership receipt restores a gate after its header changed", () => {
            var p = AndroidForgePreparation.Prepare(AndroidFixture(), out _);
            p.Files.Single(f => f.Path == AndroidForgePreparation.GatePath).Content = "// accidentally edited by a model";
            p = JsonEx.Clone(p); var restored = AndroidForgePreparation.Prepare(p, out _);
            Equal(AndroidForgePreparation.QualityGate, restored.Files.Single(f => f.Path == AndroidForgePreparation.GatePath).Content);
        });
        await Test("043: project-owned gate collision is preserved and managed path is separate", () => {
            var p = AndroidFixture(); string foreign = "// project owned\ntasks.register('projectOwnedCheck') {}\n";
            p.Files.Add(new() { Path = AndroidForgePreparation.GatePath, Content = foreign });
            p = AndroidForgePreparation.Prepare(p, out _);
            Equal(foreign, p.Files.Single(f => f.Path == AndroidForgePreparation.GatePath).Content);
            True(p.ManagedBuildFiles.Keys.Single().StartsWith("app/vanta-managed-quality-"));
            True(p.Files.Single(f => f.Path == "app/build.gradle").Content.Contains(p.ManagedBuildFiles.Keys.Single()[4..]));
        });
        await Test("043: managed source preparation is idempotent", () => {
            var p = AndroidForgePreparation.Prepare(AndroidFixture(), out _);
            var again = AndroidForgePreparation.Prepare(p, out var changes);
            Equal(ProjectFiles.Fingerprint(p), ProjectFiles.Fingerprint(again)); Equal(0, changes.Count);
        });
        await Test("043: model output cannot overwrite an owned or a genuinely foreign gate", () => {
            var original = AndroidFixture(); original.Files.Add(new() { Path = AndroidForgePreparation.GatePath, Content = "// foreign script" });
            var changes = new ProjectRecord { Files = new() { new() { Path = AndroidForgePreparation.GatePath, Content = "// overwritten" }, new() { Path = "app/vanta-managed-quality-0123456789abcdef.gradle", Content = "// forged receipt" } } };
            AndroidForgePreparation.PreserveManaged(original, changes);
            Equal(1, changes.Files.Count); Equal("// foreign script", changes.Files.Single().Content);
        });
        await Test("043: duplicate Gradle DSLs are rejected instead of guessed", () => {
            var p = AndroidFixture(); p.Files.Add(new() { Path = "app/build.gradle.kts", Content = "// conflict" });
            Reject<ForgeFailure>(() => AndroidForgePreparation.Prepare(p, out _));
        });
        await Test("043: failures cannot be disabled to manufacture build success", () => {
            var p = AndroidFixture(); p.Files[2].Content += "\ntasks.withType(Test) { ignoreFailures = true }";
            Reject<ForgeFailure>(() => AndroidForgePreparation.Prepare(p, out _));
        });
        await Test("043: AndroidX is enabled only when absent and never silently overrides explicit false", () => {
            var p = AndroidFixture(); p.Files[2].Content += "\ndependencies { implementation 'androidx.core:core:1.13.1' }";
            var prepared = AndroidForgePreparation.Prepare(p, out _);
            True(prepared.Files.Single(f => f.Path == "gradle.properties").Content.Contains("android.useAndroidX=true"));
            prepared.Files.Single(f => f.Path == "gradle.properties").Content = "android.useAndroidX=false";
            Reject<ForgeFailure>(() => AndroidForgePreparation.Prepare(prepared, out _));
        });
        await Test("043: missing Material XML dependency is fixed from actual linker diagnostics only", () => {
            var p = AndroidFixture(); string log = "Android resource linking failed\nerror: resource style/TextAppearance.Material3.BodyLarge not found";
            True(!AndroidForgePreparation.RepairMaterial(p, "The model recommends Material3"));
            True(AndroidForgePreparation.RepairMaterial(p, log));
            True(p.Files[2].Content.Contains("com.google.android.material:material:1.12.0"));
            True(!AndroidForgePreparation.RepairMaterial(p, log));
        });
        await Test("043: zero test reports are not represented as passing tests", () => {
            True(AndroidForgePreparation.TestSummary(":app:testDebugUnitTest NO-SOURCE").Contains("no executed unit-test count"));
            True(AndroidForgePreparation.TestSummary("VANTA_TEST_REPORT|v1|:app:testDebugUnitTest|4|2|1|1").Contains("4 total, 2 passed, 1 failed, 1 skipped"));
        });
        await Test("043: private-key detector source does not reject itself but actual PEM remains blocked", () => {
            ProjectFiles.Validate(new[] { new ProjectFile { Path = "Detector.cs", Content = "if (text.Contains(\"PRIVATE KEY-----\")) { return; }" } });
            string pem = "-----BEGIN " + "PRIVATE KEY-----\n" + new string('A', 128) + "\n-----END PRIVATE KEY-----";
            Reject<VantaException>(() => ProjectFiles.Validate(new[] { new ProjectFile { Path = "Secret.txt", Content = pem } }));
        });
        await Test("043: complete target retained while huge optional dependencies are explicitly excerpted", () => {
            var m = Model(); var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.Request = new string('r', 50000); p.Specification = new string('s', 50000);
            p.Files.Add(new() { Path = "Large.cs", Content = new string('D', 90000) });
            string packed = ForgeContext.Pack(m, p, "Program.cs", "Fix compiler diagnostics", new string('E', 70000), new[] { "Large.cs" });
            True(packed.Contains(p.Files.Single(f => f.Path == "Program.cs").Content)); True(packed.Length <= ForgeContext.Budget(m)); True(packed.Contains("EXCERPT"));
        });
        await Test("043: oversized mandatory target is rejected locally, never truncated", () => {
            var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.Files.Single(f => f.Path == "Program.cs").Content = new string('x', 40000);
            Reject<ForgeFailure>(() => ForgeContext.Pack(Model(), p, "Program.cs", "", ""));
        });
        await Test("043: watchdog terminates an idle request without a hidden retry", async () => {
            int calls = 0;
            await RejectAsync<ForgeFailure>(async () => { await ForgeWatchdog.RunAsync<int>(async (update, ct) => { calls++; await Task.Delay(5000, ct); return 1; }, _ => {}, CancellationToken.None, TinyLimits()); });
            Equal(1, calls);
        });
        await Test("043: transport heartbeat/repeated text does not reset answer idle timer", async () => {
            string saved = "";
            await RejectAsync<ForgeFailure>(async () => { await ForgeWatchdog.RunAsync<int>(async (update, ct) => { while (true) { update("partial answer"); await Task.Delay(20, ct); } }, text => saved = text, CancellationToken.None, TinyLimits()); });
            Equal("partial answer", saved);
        });
        await Test("043: explicit cancellation does not become an automatic provider replay", async () => {
            using var cancel = new CancellationTokenSource(50); int calls = 0;
            await RejectAsync<OperationCanceledException>(async () => { await ForgeWatchdog.RunAsync<int>(async (update, ct) => { calls++; await Task.Delay(5000, ct); return 1; }, _ => {}, cancel.Token, TinyLimits()); });
            Equal(1, calls);
        });
        await Test("043: overall watchdog expires even while answer text keeps growing", async () => {
            var limits = new ForgeRequestLimits { Idle = TimeSpan.FromSeconds(2), Overall = TimeSpan.FromMilliseconds(150), Tick = TimeSpan.FromMilliseconds(10) };
            await RejectAsync<ForgeFailure>(async () => { await ForgeWatchdog.RunAsync<int>(async (update, ct) => { string value = ""; while (true) { value += "x"; update(value); await Task.Delay(10, ct); } }, _ => {}, CancellationToken.None, limits); });
        });
        await Test("043: successful watchdog request returns the actual result", async () => {
            Equal(42, await ForgeWatchdog.RunAsync<int>((update, ct) => { update("complete"); return Task.FromResult(42); }, _ => {}, CancellationToken.None, TinyLimits()));
        });
        await Test("043: task-specific source wins over a stale general project copy", () => {
            using var service = Service(); var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.ModelKey = Model().Key;
            var j = SeedForge(service, p); var stale = JsonEx.Clone(p); stale.Files.Single(f => f.Path == "Program.cs").Content = "stale global copy";
            service.Store.Save("project", stale.Id, stale);
            Equal(p.Files.Single(f => f.Path == "Program.cs").Content, service.OpenTaskSource(j.Id).Files.Single(f => f.Path == "Program.cs").Content);
        });
        await Test("043: changing the repair model retains completed file records and the project choice", async () => {
            var replacement = Model("featherless", "qa-long-context"); replacement.Context = 262144;
            using var s = Service(models: Defaults().Append(replacement)); var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.ModelKey = Model().Key;
            var j = SeedForge(s, p); s.Store.Save("job-step", j.Id + ":author-file-fixture", new JsonObject { ["saved"] = "completed source" });
            s.ChangeForgeModel(j.Id, replacement); Equal("Blocked", (await Finish(s.Jobs, j.Id)).State);
            Equal(replacement.Key, s.Jobs.Get(j.Id)!.ModelKey); Equal(replacement.Key, s.TaskSource(j.Id).ModelKey);
            Equal("completed source", s.Store.Read<JsonObject>("job-step", j.Id + ":author-file-fixture").Str("saved"));
        });
        await Test("043: building saved source does not require an AI provider merely to reach tool preflight", async () => {
            var h = Sync(_ => throw new Exception("No provider request expected")); using var s = Service(h);
            var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.ModelKey = "featherless|unavailable";
            Equal("Blocked", (await Finish(s.Jobs, s.Rebuild(p).Id)).State); Equal(0, h.Calls);
        });
        await Test("043: all planned mandatory contexts checked before any file-generation request", async () => {
            var h = Sync(_ => Answer(new JsonObject { ["files"] = new JsonArray(new JsonObject { ["path"] = "Program.cs", ["purpose"] = "fix" }, new JsonObject { ["path"] = "Huge.cs", ["purpose"] = "fix" }) }.ToJsonString()));
            using var s = Service(h); s.Store.Save("settings", "build", new BuildPreferences { TrustLocalProjects = true });
            var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.ModelKey = Model().Key; p.Files.Add(new() { Path = "Huge.cs", Content = new string('x', 40000) });
            var j = SeedForge(s, p, new BuildReport(false, "", "", "Program.cs: compiler fixture error", "Not passed", "Controlled compiler fixture"));
            s.Resume(j.Id); var done = await Finish(s.Jobs, j.Id); True(done.Error.Contains("Context preflight"), done.Error); Equal(1, h.Calls);
        });
        await Test("043: interrupted per-file generation resumes only the unfinished file", async () => {
            int count = 0;
            var handler = new Handler(async (request, ct) => {
                int call = Interlocked.Increment(ref count);
                if (call == 1) return Answer("Small Windows source specification");
                if (call == 2) return Answer("{\"name\":\"Checkpoint fixture\",\"files\":[{\"path\":\"Program.cs\",\"purpose\":\"application\"},{\"path\":\"App.csproj\",\"purpose\":\"project\"}]}");
                if (call == 3) return Answer(new JsonObject { ["path"] = "Program.cs", ["content"] = "Console.WriteLine(\"checkpoint retained\");" }.ToJsonString());
                if (call == 4) { await Task.Delay(5000, ct); throw new Exception("watchdog failed"); }
                if (call == 5) return Answer(new JsonObject { ["path"] = "App.csproj", ["content"] = "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework><OutputType>Exe</OutputType><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>" }.ToJsonString());
                throw new Exception("Unexpected extra generation request " + call);
            });
            using var s = Service(handler); s.ForgeLimits = TinyLimits();
            var j = s.Forge("Create a Windows checkpoint fixture", "Windows", "Select model", Model().Key, null, new());
            var stopped = await Finish(s.Jobs, j.Id); True(stopped.Error.Contains("stalled"), stopped.Error); Equal(4, count);
            True(s.Store.Ids("job-step").Any(x => x.Contains(":author-initial-file-")));
            s.Resume(j.Id); Equal("Blocked", (await Finish(s.Jobs, j.Id)).State); Equal(5, count); Equal(2, s.TaskSource(j.Id).Files.Count);
        });
        await Test("043: import and selected project survive closing/recreating the native Forge view", async () => {
            using var service = Service(); var shell = new MainWindow(service) { TestLifetime = true }; shell.Show(); await Layout();
            try {
                var source = ProjectFiles.Parse(SourceJson(false), "Windows"); source.ModelKey = Model().Key;
                string zip = Path.Combine(Profile(), "project.zip"); ProjectFiles.Export(source, zip);
                var page = new ForgePage(shell); await page.Import(zip); page.Shutdown();
                string projectId = service.Store.Read<JsonObject>("draft", "forge").Str("project_id"); True(projectId.Length > 0); True(service.Store.Read<ProjectRecord>("project", projectId) != null);
                var reopened = new ForgePage(shell); Equal("Existing project", reopened.ProjectMode.SelectedItem!.ToString());
                reopened.ProjectMode.SelectedItem = "New project"; reopened.Shutdown();
                var fresh = new ForgePage(shell); Equal("New project", fresh.ProjectMode.SelectedItem!.ToString()); fresh.Shutdown();
            } finally { shell.Close(); }
        });
        await Test("043: production gate fixture is emitted for real Gradle positive and negative execution", () => {
            var p = AndroidFixture(); p.Files.Add(new() { Path = AndroidForgePreparation.GatePath, Content = "// PROJECT OWNED: must remain unchanged\ntasks.register('projectOwnedCheck') { doLast { println('FOREIGN_SCRIPT_RETAINED') } }\n" });
            p.Files[2].Content += "\napply from: 'vanta-quality.gradle'\n";
            var prepared = AndroidForgePreparation.Prepare(p, out _); string folder = Path.Combine(Evidence, "gate-fixture");
            foreach (var file in prepared.Files) { string path = Path.Combine(folder, file.Path.Replace('/', Path.DirectorySeparatorChar)); Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllBytes(path, file.Bytes()); }
            File.WriteAllText(Path.Combine(Evidence, "gate-fixture-fingerprint.txt"), ProjectFiles.Fingerprint(prepared));
            True(File.Exists(Path.Combine(folder, "app", "build.gradle")));
        });
    }
}
