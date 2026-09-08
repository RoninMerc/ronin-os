from pathlib import Path
root=Path('ronin-vanta-windows')
def edit(path,old,new):
 p=root/path;s=p.read_text(encoding='utf-8');assert s.count(old)==1,(path,s.count(old),old[:100]);p.write_text(s.replace(old,new),encoding='utf-8')
# Windows holds SQLite files open with write sharing. Inspect at rest after the reader closes.
edit('tests/Vanta.Tests/CoreTests.cs','''using var r = new Store(path); Equal("fixture-private-credential-91234", r.RequireKey("venice")); Equal("fixture-private-draft-56789", r.Read<JsonObject>("draft", "x").Str("text")); True(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(path, "vanta.sqlite"))).Contains("fixture-private")); r.SetKey("venice", ""); True(!r.Configured("venice"));''','''using(var r = new Store(path)){ Equal("fixture-private-credential-91234", r.RequireKey("venice")); Equal("fixture-private-draft-56789", r.Read<JsonObject>("draft", "x").Str("text")); }
            foreach(var f in Directory.GetFiles(path,"vanta.sqlite*"))True(!Encoding.UTF8.GetString(File.ReadAllBytes(f)).Contains("fixture-private"));
            using(var r=new Store(path)){r.SetKey("venice", ""); True(!r.Configured("venice"));}''')
# Explicit metadata from either supported provider schema location, never a name-derived capability.
edit('src/Vanta.Core/Domain.cs','    public bool Executable(string mode)', '    public string MediaInputType => Spec.Obj("constraints").Str("model_type",Spec.Str("model_type"));\n    public bool Executable(string mode)')
edit('src/Vanta.Core/Domain.cs','Spec.Obj("constraints").Str("model_type") is "image-edit" or "upscale"','MediaInputType is "image-edit" or "upscale"')
edit('src/Vanta.Core/Domain.cs','Spec.Obj("constraints").Str("model_type") == "text-to-video"','MediaInputType == "text-to-video"')
# An implicit Window style does not style a derived MainWindow class in WPF.
edit('src/Vanta.Windows/MainWindow.cs','        Active = this; Services = services;','        SetResourceReference(StyleProperty, typeof(Window));\n        Active = this; Services = services;')
# Let text inside a primary button inherit its dark foreground rather than forcing white.
edit('src/Vanta.Windows/Theme.xaml','<Style TargetType="TextBlock"><Setter Property="Foreground" Value="{StaticResource Text}"/>','<Style TargetType="TextBlock">')
# Visible empty input hint (not merely a tooltip), hidden immediately after typing.
edit('src/Vanta.Windows/Ui.cs','ToolTip = hint, AcceptsReturn = multiline','ToolTip = hint, Tag = hint, AcceptsReturn = multiline')
edit('src/Vanta.Windows/Theme.xaml','''<ScrollViewer x:Name="PART_ContentHost" Margin="{TemplateBinding Padding}"/></Border><ControlTemplate.Triggers>''','''<Grid><ScrollViewer x:Name="PART_ContentHost" Margin="{TemplateBinding Padding}"/><TextBlock x:Name="Hint" Text="{TemplateBinding Tag}" Margin="{TemplateBinding Padding}" Foreground="{StaticResource Muted}" Opacity="0.75" IsHitTestVisible="False" Visibility="Collapsed" VerticalAlignment="Top"/></Grid></Border><ControlTemplate.Triggers><Trigger Property="Text" Value=""><Setter TargetName="Hint" Property="Visibility" Value="Visible"/></Trigger>''')
# Case-insensitive secret/VCS paths follow Windows filesystem semantics.
edit('src/Vanta.Core/Projects.cs','p == ".env" || p.StartsWith(".env.", StringComparison.OrdinalIgnoreCase) || p is ".aws" or ".ssh"','new[]{".env",".aws",".ssh"}.Contains(p,StringComparer.OrdinalIgnoreCase) || p.StartsWith(".env.", StringComparison.OrdinalIgnoreCase)')
edit('src/Vanta.Core/Projects.cs','p is "." or ".." or ".git" or ".svn"','new[]{".","..",".git",".svn"}.Contains(p,StringComparer.OrdinalIgnoreCase)')
# Cancellation can race a completed task disposing its token source.
edit('src/Vanta.Core/Jobs.cs','    public void Cancel(string id)','    private static void SafeCancel(CancellationTokenSource source){try{source.Cancel();}catch(ObjectDisposedException){}}\n    public void Cancel(string id)')
edit('src/Vanta.Core/Jobs.cs','source)) source.Cancel();','source)) SafeCancel(source);')
p=root/'src/Vanta.Core/Jobs.cs';s=p.read_text();s=s.replace('foreach (var c in cancellation.Values) c.Cancel();','foreach (var c in cancellation.Values) SafeCancel(c);');p.write_text(s)
edit('src/Vanta.Core/Jobs.cs','        Update(id, j => { j.State = "Queued"; j.Error = ""; });','        Store.Delete("job-result",id);\n        Update(id, j => { j.State = "Queued"; j.Error = ""; });')
# A previously completed task is not resumable. Rebuild saved source as a separate recorded version.
edit('src/Vanta.Core/Workflows.cs','    private async Task ForgeOperation(JobContext c, ModelRecord model)','''    public JobRecord Rebuild(ProjectRecord source)
    {
        ProjectFiles.Validate(source.Files);
        if(Jobs.Snapshot().Any(j=>j.ProjectId==source.Id&&!j.Terminal))throw new VantaException("This project already has active work.");
        var model=Models.Find(source.ModelKey);
        if(model==null||!model.Executable("forge")||!Store.Configured(model.ProviderId))throw new VantaException("The project's original coding model is unavailable.","Open the project in Forge and select an available model.");
        var project=JsonEx.Clone(source);project.Id=Guid.NewGuid().ToString("N");project.Folder="";project.Artifact="";project.Distribution="";project.BuildResult="Not built";project.TestResult="Not run";project.Updated=DateTimeOffset.UtcNow;
        var job=Jobs.Create("forge","Forge rebuild · "+project.Name,model.Key,new(){["request"]=project.Request,["project"]=JsonSerializer.SerializeToNode(project),["attachments"]=new JsonArray()},project:project.Id);
        Store.Save("project",project.Id,project);Store.Save("job-step",job.Id+":source",project);Models.Remember(model);Jobs.Start(job.Id,c=>ForgeOperation(c,model));return job;
    }
    private async Task ForgeOperation(JobContext c, ModelRecord model)''')
edit('src/Vanta.Core/Workflows.cs','project.Folder = ""; project.Artifact = ""; }','project.Folder = ""; project.Artifact = ""; project.Distribution="";project.BuildResult="Not built";project.TestResult="Not run"; }')
old=''', Ui.Button("Resume build", () => { if (job.Length == 0) throw new VantaException("Describe the requested changes and use Build to create a task first."); Services.Resume(job); Composer.Send.IsEnabled = false; })'''
edit('src/Vanta.Windows/ForgePage.cs',old,'')
edit('src/Vanta.Windows/ForgePage.cs','        if (p.Folder.Length > 0) box.Children.Add','''        var prior=job.Length>0?Services.Jobs.Get(job):null;
        if(persisted&&prior is {Terminal:true}&&prior.State!="Complete")box.Children.Add(Ui.Row(Ui.Button("Resume interrupted build",()=>{Services.Resume(job);Composer.Send.IsEnabled=false;})));
        if(persisted&&p.Files.Count>0&&(prior==null||prior.Terminal))box.Children.Add(Ui.Row(Ui.Button("Build saved source",()=>{
            var next=Services.Rebuild(p);job=next.Id;current=Services.Store.Read<ProjectRecord>("project",next.ProjectId);Composer.Send.IsEnabled=false;results.Children.Clear();progress.Visibility=Visibility.Visible;progress.IsIndeterminate=true;status.Text="Building saved source. Compiler checks and review will run.";
        })));
        if (p.Folder.Length > 0) box.Children.Add''')
edit('src/Vanta.Windows/ForgePage.cs','Source saved. Resume to rebuild; this edit does not update the previous binary.','Source saved. Close the editor and choose Build saved source. The previous binary is unchanged.')
edit('src/Vanta.Windows/ForgePage.cs','window.ShowDialog();\n    }\n    private void History()','window.ShowDialog(); ShowProject(p,persisted);\n    }\n    private void History()')
# Preserve every previous assertion and add coverage for each functional correction.
edit('tests/Vanta.Tests/CoreTests.cs','        await Test("OpenAI video seconds and size stay in supported enums",','''        await Test("Venice nested video metadata selects required parameters",()=>{var m=Model("venice","kling-fixture","video");m.Raw["model_spec"]!["constraints"]=Obj("{\\"model_type\\":\\"text-to-video\\",\\"durations\\":[\\"5s\\",\\"10s\\"],\\"aspect_ratios\\":[\\"16:9\\",\\"9:16\\"]}");True(m.Executable("video"));Equal("5s",VideoOptions.For(m).Request(m,"Moving shapes").Str("duration"));m.Raw["model_spec"]!["constraints"]!["model_type"]="image-to-video";True(!m.Executable("video"));});
        await Test("Windows case variants cannot attach secrets or VCS internals",()=>{foreach(var path in new[]{".ENV",".SSH/key.txt",".GIT/config",".SVN/entries"})Reject<VantaException>(()=>ProjectFiles.Normalize(path));});
        await Test("OpenAI video seconds and size stay in supported enums",''')
edit('tests/Vanta.Tests/WorkflowTests.cs','        await Test("Local build subprocess cancellation actually terminates",','''        await Test("Rebuild saved source preserves earlier project and performs no code generation",async()=>{var h=Sync(_=>throw new Exception("No generation should be requested before build approval"));using var s=Service(h);var source=ProjectFiles.Parse(SourceJson(false),"Windows");source.ModelKey=Model().Key;source.Request="Saved build request";s.Store.Save("project",source.Id,source);var j=s.Rebuild(source);Equal("Blocked",(await Finish(s.Jobs,j.Id)).State);True(j.ProjectId!=source.Id);Equal(0,h.Calls);True(s.Store.Read<ProjectRecord>("project",source.Id)!=null);Equal(source.Files.Count,s.Store.Read<ProjectRecord>("project",j.ProjectId)!.Files.Count);});
        await Test("Local build subprocess cancellation actually terminates",''')
edit('tests/Vanta.Tests/DesktopTests.cs','            await Test("Prompt opens with exact Huihui default without orchestration controls",','''            await Test("Derived main window inherits the dark desktop design system",async()=>{Equal(Ui.Brush("Background"),window.Background);Equal(Ui.Brush("Text"),window.Foreground);window.Navigate("Forge");await Layout();Shot("dark-forge-verified");var primary=Descendants<Button>(window).Single(b=>b.Content?.ToString()=="Build");True(primary.Foreground is SolidColorBrush brush&&brush.Color.R<60);});
            await Test("Prompt opens with exact Huihui default without orchestration controls",''')
print('Release review applied: dark surface, metadata fallback, safe rebuild action, cancellation and additional regressions.')
