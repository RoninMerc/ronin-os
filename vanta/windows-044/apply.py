from pathlib import Path
import shutil

root=Path('ronin-vanta-windows')
here=Path(__file__).parent

def edit(name, old, new, count=1):
    p=root/name
    text=p.read_text(encoding='utf-8')
    actual=text.count(old)
    if actual!=count:
        raise RuntimeError(f'{name}: expected {count} anchors, got {actual}: {old[:120]}')
    p.write_text(text.replace(old,new),encoding='utf-8')

# Add the compiler-led repair engine and its regression tests.
shutil.copyfile(here/'AgenticForgeRepair.cs', root/'src/Vanta.Core/AgenticForgeRepair.cs')
shutil.copyfile(here/'ForgeAgenticTests.cs', root/'tests/Vanta.Tests/ForgeAgenticTests.cs')

# Larger ceilings, but compiler progress and explicit circuit breakers remain authoritative.
edit('src/Vanta.Core/ForgeExecution.cs',
     'private const int ForgeRounds = 6;',
     'private const int ForgeRounds = 24;')
edit('src/Vanta.Core/ForgeExecution.cs',
     'if (c.Record.AiRequests >= 120) throw new ForgeFailure("Request budget", "The task reached its 120-request limit.", "Source and checkpoints are retained. Review the task before authorising further work.");',
     'if (c.Record.AiRequests >= 300) throw new ForgeFailure("Request budget", "The task reached its 300-request safety ceiling.", "Source, checkpoints and actual compiler diagnostics are retained. Review no-progress evidence before authorising more work.");')

# Existing/new project scaffolding still uses the planner once. Compiler repair does not.
edit('src/Vanta.Core/ForgeExecution.cs',
     'repaired = await AuthorFiles(c, NeedModel(), project, "repair-" + attempt, report.Log);',
     'repaired = await AgenticCompilerRepair(c, NeedModel(), project, attempt, report.Log);')

# Real no-progress breaker before another paid repair.
anchor='''            if (attempt == ForgeRounds)
            {
                c.Block("Six build/repair rounds failed. Source, partial checkpoints and actual diagnostics are preserved. No working artifact is claimed.", new() { ["project"] = project.Id, ["text"] = report.Log, ["tests"] = report.Tests }); return;
            }
'''
replacement='''            if (AgenticNoProgress(c, project, report.Log))
            {
                c.Block("No-progress circuit breaker: the same meaningful compiler/test failure survived three consecutive repair cycles. Source, checkpoints and diagnostics are preserved. No additional AI repair was purchased.", new() { ["project"] = project.Id, ["text"] = report.Log, ["tests"] = report.Tests }); return;
            }
            if (attempt == ForgeRounds)
            {
                c.Block("The " + ForgeRounds + "-round build safety ceiling was reached. Source, partial checkpoints and actual diagnostics are preserved. No working artifact is claimed.", new() { ["project"] = project.Id, ["text"] = report.Log, ["tests"] = report.Tests }); return;
            }
'''
edit('src/Vanta.Core/ForgeExecution.cs',anchor,replacement)

# UI displays the real expanded request ceiling and scans all compiler rounds.
edit('src/Vanta.Windows/ActivityPage.cs','"\\nAI requests: " + j.AiRequests + "/120"','"\\nAI requests: " + j.AiRequests + "/300"')
edit('src/Vanta.Windows/ActivityPage.cs','for (int round = 1; round <= 6; round++)','for (int round = 1; round <= 24; round++)')

# Update existing repair acceptance cases to assert the new compiler-led contract rather than the retired repair planner.
edit('tests/Vanta.Tests/WorkflowTests.cs',
     'int calls = 0; var h = new Handler(async (r, _) => { calls++; string body = await r.Content!.ReadAsStringAsync(); if (calls == 1) return Answer("Implement a Windows console application."); if (calls == 2) return Answer(SourceJson(true)); if (body.Contains("ACTUAL BUILD LOG")) return Answer(SourceJson(false)); return Answer("{\\\"approved\\\":true,\\\"notes\\\":\\\"Compiler evidence reviewed; generated-app runtime tests were not provided.\\\",\\\"files\\\":[]}"); });',
     'int calls = 0; var h = new Handler(async (r, ct) => { calls++; var body = await r.Content!.ReadAsStringAsync(ct); if (calls == 1) return Answer("Implement a Windows console application."); if (calls == 2) return Answer(SourceJson(true)); if (calls == 3) return Answer(new JsonObject { ["path"] = "VantaProbe.csproj", ["content"] = "<Project Sdk=\\\"Microsoft.NET.Sdk\\\"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>", ["encoding"] = "utf-8" }.ToJsonString()); if (calls == 4) return Answer(new JsonObject { ["path"] = "Program.cs", ["content"] = "Console.WriteLine(\\\"VANTA_REAL_BUILD_OK\\\");\\n// compiler-led repair", ["encoding"] = "utf-8" }.ToJsonString()); throw new Exception("Unexpected repair target at request " + calls); });')

p=root/'tests/Vanta.Tests/ForgeReliabilityTests.cs'
text=p.read_text(encoding='utf-8')
start=text.index('        await Test("043: all planned mandatory contexts checked before any file-generation request"')
end=text.index('        await Test("043: interrupted per-file generation resumes only the unfinished file"', start)
replacement='''        await Test("044: compiler-led repair skips the retired repair-plan call and leaves unrelated large source intact", async () => {
            int calls = 0;
            var h = Sync(r => {
                calls++;
                var ignored = r.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
                if (calls == 1)
                    return Answer(new JsonObject {
                        ["path"] = "Program.cs",
                        ["content"] = "Console.WriteLine(\\\"VANTA_REAL_BUILD_OK\\\");\\n// targeted repair",
                        ["encoding"] = "utf-8"
                    }.ToJsonString());
                if (calls == 2)
                    return Answer(new JsonObject {
                        ["path"] = "VantaProbe.csproj",
                        ["content"] = "<Project Sdk=\\\"Microsoft.NET.Sdk\\\"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>",
                        ["encoding"] = "utf-8"
                    }.ToJsonString());
                throw new Exception("Unexpected targeted repair call " + calls);
            });
            using var s = Service(h); s.Store.Save("settings", "build", new BuildPreferences { TrustLocalProjects = true });
            var p = ProjectFiles.Parse(SourceJson(false), "Windows"); p.ModelKey = Model().Key;
            p.Files.Add(new() { Path = "Huge.cs", Content = "/* unrelated optional source\\n" + new string('x', 40000) + "\\n*/" });
            var j = SeedForge(s, p, new BuildReport(false, "", "", "Program.cs: compiler fixture error", "Not passed", "Controlled compiler fixture"));
            s.Resume(j.Id); var done = await Finish(s.Jobs, j.Id, 120);
            True(done.State == "Complete", done.Error + "\\n" + string.Join("\\n", done.Events.Select(x => x.Stage + ": " + x.Text))); Equal(1, calls);
            var saved = s.TaskSource(j.Id);
            Equal(40032, saved.Files.Single(f => f.Path == "Huge.cs").Content.Length);
            True(saved.Files.Single(f => f.Path == "Program.cs").Content.Contains("targeted repair"));
        });
'''
text=text[:start]+replacement+text[end:]
p.write_text(text,encoding='utf-8')

# Surface complete task diagnostics from both end-to-end Forge acceptance checks.
edit('tests/Vanta.Tests/WorkflowTests.cs',
     'var done = await Finish(s.Jobs, j.Id, 300); Equal("Complete", done.State); True(done.Events.Any(x => x.Stage == "Fixing"));',
     'var done = await Finish(s.Jobs, j.Id, 300); True(done.State == "Complete", done.Error + "\\n" + string.Join("\\n", done.Events.Select(x => x.Stage + ": " + x.Text))); True(done.Events.Any(x => x.Stage == "Fixing"));')
# Run agentic regression tests in the native acceptance harness.
edit('tests/Vanta.Tests/Program.cs',
     'await FeatherlessContractTests(); await ForgeReliabilityTests();',
     'await FeatherlessContractTests(); await ForgeReliabilityTests(); await ForgeAgenticTests();')

# Update visible/native version markers.
for relative in [
    'Directory.Build.props',
    'installer.iss',
    'src/Vanta.Windows/MainWindow.cs',
    'src/Vanta.Windows/SettingsPage.cs',
    'src/Vanta.Windows/app.manifest',
    'tests/Vanta.Tests/CatalogueAcceptanceTests.cs'
]:
    p=root/relative
    text=p.read_text(encoding='utf-8')
    p.write_text(text.replace('0.4.3','0.4.4'),encoding='utf-8')

# Package script is generated by the 0.4.3 reconstruction pass; clone it for this build.
base=Path('vanta/windows-043/package.ps1')
if not base.exists():
    raise RuntimeError('0.4.3 package script was not produced by the reconstruction pass')
(here/'package.ps1').write_text(base.read_text(encoding='utf-8').replace('0.4.3','0.4.4'),encoding='utf-8')

print('Windows 0.4.4: compiler-led Agentic Forge repair enabled. Initial scaffold remains unchanged; failed compiler rounds repair at most three implicated production files before rebuilding.')
