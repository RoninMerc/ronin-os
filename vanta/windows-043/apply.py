from pathlib import Path
import shutil
root=Path('ronin-vanta-windows'); here=Path(__file__).parent

def edit(path,old,new,count=1):
    p=root/path; text=p.read_text(encoding='utf-8')
    if text.count(old)!=count: raise RuntimeError(f'{path}: expected {count}, found {text.count(old)}: {old[:100]}')
    p.write_text(text.replace(old,new),encoding='utf-8')

for name in ['ForgeReliability.cs','ForgeExecution.cs']:
    shutil.copyfile(here/name,root/'src/Vanta.Core'/name)
shutil.copyfile(here/'ForgeReliabilityTests.cs',root/'tests/Vanta.Tests/ForgeReliabilityTests.cs')
edit('src/Vanta.Core/Projects.cs','public List<ProjectFile> Files { get; set; } = [];','public Dictionary<string,string> ManagedBuildFiles { get; set; } = new(StringComparer.Ordinal);\n    public List<ProjectFile> Files { get; set; } = [];')
edit('src/Vanta.Core/Projects.cs','file.Content.Contains("PRIVATE KEY-----", StringComparison.Ordinal)',r'''Regex.IsMatch(file.Content, @"(?m)^\s*-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s*\r?\n[A-Za-z0-9+/=\r\n]{32,}-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")''')
edit('src/Vanta.Core/Projects.cs','    public static List<ProjectFile> Merge(IEnumerable<ProjectFile> original,IEnumerable<ProjectFile> changes) {','''    public static List<ProjectFile> MergeUnchecked(IEnumerable<ProjectFile> original,IEnumerable<ProjectFile> changes) {
        var files=original.ToDictionary(f=>Normalize(f.Path),StringComparer.OrdinalIgnoreCase);
        foreach(var file in changes) files[Normalize(file.Path)]=file;
        return files.Values.ToList();
    }
    public static List<ProjectFile> Merge(IEnumerable<ProjectFile> original,IEnumerable<ProjectFile> changes) {''')
edit('src/Vanta.Core/Projects.cs','using var zip = ZipFile.OpenRead(path);','''if (new FileInfo(path).Length > 32L * 1024 * 1024) throw new VantaException("Source archive exceeds the 32 MiB import limit. No project was replaced.");
            using var zip = ZipFile.OpenRead(path);''')
edit('src/Vanta.Core/Jobs.cs','public int Attempt { get; set; } = 1;','public int Attempt { get; set; } = 1; public int AiRequests { get; set; }')
edit('src/Vanta.Core/Workflows.cs','''        if(model==null||!model.Executable("forge")||!Store.Configured(model.ProviderId))throw new VantaException("The project's original coding model is unavailable.","Open the project in Forge and select an available model.");\n''','')
edit('src/Vanta.Core/Workflows.cs','"Forge rebuild · "+project.Name,model.Key,','"Forge rebuild · "+project.Name,project.ModelKey,')
edit('src/Vanta.Core/Workflows.cs','["manual_model"]=model.Key,','["manual_model"]=project.ModelKey,')
edit('src/Vanta.Core/Workflows.cs','Store.Save("job-step",job.Id+":source",project);Models.Remember(model);','Store.Save("job-step",job.Id+":source",project);if(model!=null)Models.Remember(model);')
p=root/'src/Vanta.Core/Workflows.cs';text=p.read_text();a=text.index('    private async Task ForgeOperation(');b=text.index('    private static void Merge(',a)
text=text[:a]+'    private Task ForgeOperation(JobContext c, ModelRecord? model) => ExecuteForge(c, model);\n'+text[b:];p.write_text(text,encoding='utf-8')
edit('src/Vanta.Core/Workflows.cs','        if (model == null) throw new VantaException("The task\'s original model is not in the catalogue.", "Refresh the provider before resuming.");','''        if (job.Type == "forge") { Jobs.Start(id, c => ForgeOperation(c, model)); return; }
        if (model == null) throw new VantaException("The task's original model is not in the catalogue.", "Refresh the provider before resuming.");''')
edit('src/Vanta.Core/ForgeExecution.cs','PlanInstructions + "\\n" + PromptStrategy.ForgeCode(original.Platform)','PlanInstructions + "\\nTarget platform: " + original.Platform + ". Windows projects use a pinned supported .NET project; Android projects include settings, root and app Gradle files, manifest, resources, application source and real unit tests. Do not add unsupported host scripts, secrets or private keys."')
edit('src/Vanta.Core/ForgeReliability.cs','Math.Min(model.MaxOutput, 8192) : 8192','Math.Min(model.MaxOutput, 16384) : 16384')
# Identifiers bind every build request to the exact source snapshot. Existing matching submissions are retrieved, never overwritten.
edit('src/Vanta.Core/RemoteBuilder.cs','''        string requestId = job.Id + "-" + attempt; string path = "vanta-forge/requests/" + requestId + ".json";
        var remote = job.Record.Remote;
        string sha = remote.Str("sha");
        if (remote.Str("request_id") != requestId) sha = "";''','''        string fingerprint = ProjectFiles.Fingerprint(project);
        var remote = job.Record.Remote;
        string requestId = job.Id + "-" + attempt + "-" + fingerprint[..12].ToLowerInvariant();
        // Retain a known matching upload across restart. Old unbound requests are left untouched.
        if (remote.Num("attempt") == attempt && remote.Str("source_fingerprint") == fingerprint && remote.Str("request_id").Length > 0) requestId = remote.Str("request_id");
        string path = "vanta-forge/requests/" + requestId + ".json";
        string sha = remote.Str("request_id") == requestId && remote.Str("source_fingerprint") == fingerprint ? remote.Str("sha") : "";''')
edit('src/Vanta.Core/RemoteBuilder.cs','["attempt"] = attempt });','["attempt"] = attempt, ["source_fingerprint"] = fingerprint });')
edit('src/Vanta.Core/RemoteBuilder.cs','["attempt"] = attempt, ["sha"] = sha','["attempt"] = attempt, ["source_fingerprint"] = fingerprint, ["sha"] = sha')
edit('src/Vanta.Core/RemoteBuilder.cs','"Worker ran compilation and lint. It does not report generated-app unit or runtime tests. Debug APK signing belongs to this worker build."','AndroidForgePreparation.TestSummary(log) + " Debug APK signing belongs to this worker build; signing continuity and independent cryptographic signature verification are not supplied by this Windows client."')
# Current source survives closing the Forge view or a new conversation; New remains explicitly clean.
edit('src/Vanta.Windows/ForgePage.cs','current = p; ShowProject(p, false);','current = p; Services.Store.Save("project", p.Id, p); ShowProject(p, true); SaveDraft();')
edit('src/Vanta.Windows/ForgePage.cs','private void SaveDraft() => Services.Store.Save("draft", "forge", new JsonObject { ["text"] = Composer.Input.Text, ["platform"] = Platform.SelectedItem?.ToString() });','''private void SaveDraft() => Services.Store.Save("draft", "forge", new JsonObject { ["text"] = Composer.Input.Text, ["platform"] = Platform.SelectedItem?.ToString(), ["project_mode"] = ProjectMode.SelectedItem?.ToString(), ["project_id"] = imported?.Id ?? "", ["manual_model"] = manual, ["model_mode"] = ModelMode.SelectedItem?.ToString() });''')
p=root/'src/Vanta.Windows/ForgePage.cs';text=p.read_text();needle='        UpdateImport(); UpdateModel();'
if text.count(needle)!=1: raise RuntimeError('Forge constructor restore anchor')
text=text.replace(needle,'''        if (draft != null && draft.Str("project_mode") == "Existing project" && draft.Str("project_id").Length > 0) {
            var saved = Services.Store.Read<ProjectRecord>("project", draft.Str("project_id"));
            if (saved != null) { imported = current = saved; ProjectMode.SelectedItem = "Existing project"; ShowProject(saved, true); }
        }
        UpdateImport(); UpdateModel();''');p.write_text(text,encoding='utf-8')
edit('src/Vanta.Windows/ForgePage.cs','ProjectMode.SelectedItem = "Existing project"; Platform.SelectedItem = current.Platform; Composer.Input.Text = current.Request;','''ProjectMode.SelectedItem = "Existing project"; Platform.SelectedItem = current.Platform; Composer.Input.Text = current.Request;
        if (current.ModelKey.Length > 0) { manual = current.ModelKey; ModelMode.SelectedItem = "Select model"; SaveMode(); }
        SaveDraft();''')
edit('src/Vanta.Windows/ForgePage.cs','Building saved source. Compiler checks and review will run.','Building the saved source snapshot. Real compiler/tests run; source repairs retain this project’s selected model.')
edit('src/Vanta.Windows/ForgePage.cs','The existing worker returns a debug-signed APK. Its signing identity is not guaranteed across worker runs; generated-app unit/runtime tests are not supplied by that worker.','The worker returns a debug-signed test APK. Vanta’s managed gate requests unit tests and reports actual counts when present. Device tests and signing continuity across worker runs are not guaranteed. Compilation alone does not verify application behaviour.')
edit('src/Vanta.Windows/ActivityPage.cs','''if (j.ProjectId.Length > 0) controls.Children.Add(Ui.Button("Open project", () => { Shell.Navigate("Forge"); ((ForgePage)Shell.CurrentPage!).OpenProject(j.ProjectId); }));''','''if (j.ProjectId.Length > 0 && j.Terminal) controls.Children.Add(Ui.Button("Open this task's saved source", () => { var snapshot = Services.OpenTaskSource(j.Id); Shell.Navigate("Forge"); ((ForgePage)Shell.CurrentPage!).OpenProject(snapshot.Id); }));
            if (j.Type == "forge" && j.Terminal && j.State != "Complete") controls.Children.Add(Ui.Button("Change model & resume", () => Shell.PickModel("forge", choice => { if (Ui.Confirm("Change this task's repair model?", "Resume with " + choice.Name + "? Completed file checkpoints remain. Unfinished work may use your provider credits. No other model will be selected silently.")) { Services.ChangeForgeModel(j.Id, choice); Refresh(); } }, "")));
            controls.Children.Add(Ui.Button("Full error / build log", () => ShowDiagnostics(j)));''')
edit('src/Vanta.Windows/ActivityPage.cs','"\\nAttempt: " + j.Attempt','"\\nBuild/repair round: " + j.Attempt + "\\nAI requests: " + j.AiRequests + "/120"')
edit('src/Vanta.Windows/ActivityPage.cs','    public override void Enter() => Refresh();','''    private void ShowDiagnostics(JobRecord j)
    {
        string text = "Task: " + j.Id + "\\nModel: " + j.ModelKey + "\\nState: " + j.State + "\\nBuild/repair round: " + j.Attempt + "\\nAI requests: " + j.AiRequests + "\\n\\n" + j.Error;
        for (int round = 1; round <= 6; round++) {
            var result = Services.Store.Read<JsonObject>("job-step", j.Id + ":compiler-" + round);
            if (result != null) text += "\\n\\nCOMPILER ROUND " + round + "\\n" + result.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
        }
        text += "\\n\\nEVENTS\\n" + string.Join("\\n", j.Events.Select(e => e.Time.ToString("O") + " " + e.Stage + " " + e.Text));
        text = UserErrors.Clean(text);
        var box = Ui.Input("Complete error and compiler diagnostics", true); box.IsReadOnly = true; box.Text = text; box.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        var root = Ui.Rows(Ui.Star, Ui.Auto); Ui.Place(root, box); Ui.Place(root, Ui.Row(Ui.Button("Copy full details", () => Ui.Copy(text)), Ui.Button("Save details", () => Ui.SaveText("Vanta-build-diagnostics.txt", text))), 1);
        Ui.Dialog("Full task diagnostics — review before sharing", root, 1100, 780).ShowDialog();
    }
    public override void Enter() => Refresh();''')
edit('tests/Vanta.Tests/Program.cs','await FeatherlessContractTests();','await FeatherlessContractTests(); await ForgeReliabilityTests();')
for relative in ['Directory.Build.props','installer.iss','src/Vanta.Windows/SettingsPage.cs','src/Vanta.Windows/app.manifest']:
    p=root/relative;text=p.read_text(encoding='utf-8');p.write_text(text.replace('0.4.2','0.4.3'),encoding='utf-8')
(here/'package.ps1').write_text(Path('vanta/windows-042/package.ps1').read_text(encoding='utf-8').replace('0.4.2','0.4.3'),encoding='utf-8')
print('Windows 0.4.3: managed build files, targeted checkpointed repair, context/idle bounds, exact source recovery, model continuity, diagnostic and persistent import fixes installed in the native desktop source.')
