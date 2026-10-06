using System.Diagnostics;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://127.0.0.1:8765");
builder.Services.AddSingleton<AppStore>();
builder.Services.AddSingleton<SupervisorEngine>();
var app = builder.Build();

app.MapGet("/", () => Results.Content(Dashboard.Html, "text/html"));
app.MapGet("/api/state", (SupervisorEngine e) => Results.Json(e.State));
app.MapGet("/api/settings", (AppStore s) => Results.Json(s.PublicSettings()));
app.MapPost("/api/settings", async (HttpContext c, AppStore s) => {
    var incoming = await c.Request.ReadFromJsonAsync<SettingsInput>() ?? new SettingsInput();
    s.SaveSettings(incoming);
    return Results.Ok(s.PublicSettings());
});
app.MapPost("/api/project/import", async (HttpRequest req, AppStore s) => {
    if (!req.HasFormContentType) return Results.BadRequest("multipart/form-data required");
    var form = await req.ReadFormAsync();
    var file = form.Files.FirstOrDefault();
    if (file == null) return Results.BadRequest("ZIP file required");
    var name = form["name"].FirstOrDefault() ?? Path.GetFileNameWithoutExtension(file.FileName);
    await using var input = file.OpenReadStream();
    var p = await s.ImportZip(input, name);
    return Results.Ok(new { p.Id, p.Name, files = p.Files.Count, p.Root });
});
app.MapGet("/api/projects", (AppStore s) => Results.Json(s.ListProjects()));
app.MapPost("/api/run/start/{projectId}", (string projectId, SupervisorEngine e) => e.Start(projectId) ? Results.Ok() : Results.Conflict(e.State));
app.MapPost("/api/run/pause", (SupervisorEngine e) => { e.Pause(); return Results.Ok(); });
app.MapPost("/api/run/stop", (SupervisorEngine e) => { e.Stop(); return Results.Ok(); });
app.MapGet("/api/ledger/{projectId}", (string projectId, AppStore s) => Results.Json(s.LoadLedger(projectId)));

app.Lifetime.ApplicationStarted.Register(() => {
    Console.WriteLine("Ronin Forge Supervisor: http://127.0.0.1:8765");
    try { Process.Start(new ProcessStartInfo("http://127.0.0.1:8765") { UseShellExecute = true }); } catch { }
});

await app.RunAsync();

static class Dashboard
{
    public const string Html = """
<!doctype html><html><head><meta charset="utf-8"><title>Ronin Forge Supervisor</title>
<style>
body{font-family:Segoe UI,Arial;background:#0d0f12;color:#eee;margin:0}
header{padding:24px 32px;background:#14171c;border-bottom:1px solid #2b3038}
main{max-width:1100px;margin:auto;padding:24px}.card{background:#15191f;border:1px solid #2d333d;border-radius:14px;padding:18px;margin:14px 0}
input,select,button{background:#0f1216;color:#eee;border:1px solid #3a414d;border-radius:8px;padding:10px;margin:5px}
input{min-width:290px}button{cursor:pointer;background:#c79a43;color:#111;font-weight:700}.secondary{background:#252a32;color:#eee}
pre{white-space:pre-wrap;max-height:360px;overflow:auto;background:#090b0e;padding:12px;border-radius:8px}.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
small{color:#aab2bd}</style></head>
<body><header><h1>Ronin Forge Supervisor</h1><div>Autonomous compile → diagnose → repair → rebuild</div></header><main>
<div class="card"><h2>1. Provider + worker</h2>
<div class="row"><input id="pname" value="OpenRouter" placeholder="Provider"><input id="base" value="https://openrouter.ai/api/v1" placeholder="Base URL"><input id="model" value="qwen/qwen3-coder-next" placeholder="Model"></div>
<div class="row"><input id="pkey" type="password" placeholder="Provider API key"><input id="repo" value="RoninMerc/RonisOS-BPlus" placeholder="GitHub worker repo"><input id="branch" value="vanta-forge-worker" placeholder="Worker branch"><input id="gtoken" type="password" placeholder="GitHub token"></div>
<div class="row"><select id="platform"><option value="android">Android APK</option><option value="windows">Windows</option></select><label>Max cycles <input id="max" type="number" value="50" min="1" max="500" style="min-width:80px"></label><button onclick="save()">Save settings</button></div>
<small>Keys are protected with Windows DPAPI for the current Windows user.</small></div>
<div class="card"><h2>2. Import project</h2>
<div class="row"><input id="projectName" placeholder="Project name"><input id="zip" type="file" accept=".zip"><button onclick="upload()">Import Vanta/source ZIP</button></div><div id="projects"></div></div>
<div class="card"><h2>3. Supervisor</h2>
<div class="row"><button onclick="start()">Run until success</button><button class="secondary" onclick="pause()">Pause</button><button class="secondary" onclick="stop()">Stop</button></div>
<pre id="state">Loading...</pre></div>
<script>
let current="";
async function save(){await fetch('/api/settings',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({providerName:pname.value,providerBaseUrl:base.value,providerApiKey:pkey.value,model:model.value,githubRepo:repo.value,githubBranch:branch.value,githubToken:gtoken.value,platform:platform.value,maxCycles:+max.value})});alert('Saved');}
async function upload(){let f=zip.files[0];if(!f){alert('Select a ZIP');return;}let d=new FormData();d.append('file',f);d.append('name',projectName.value||f.name);let r=await fetch('/api/project/import',{method:'POST',body:d});if(!r.ok){alert(await r.text());return;}let j=await r.json();current=j.id;await loadProjects();}
async function loadProjects(){let r=await fetch('/api/projects');let j=await r.json();projects.innerHTML=j.map(x=>'<label><input type="radio" name="p" '+(x.id==current?'checked':'')+' onclick="current=\\''+x.id+'\\'"> '+x.name+' ('+x.files.length+' files)</label><br>').join('');if(!current&&j.length)current=j[j.length-1].id;}
async function start(){if(!current){alert('Import/select a project');return;}let r=await fetch('/api/run/start/'+current,{method:'POST'});if(!r.ok)alert(await r.text());}
async function pause(){await fetch('/api/run/pause',{method:'POST'});}async function stop(){await fetch('/api/run/stop',{method:'POST'});}
async function tick(){try{let r=await fetch('/api/state');let j=await r.json();state.textContent=JSON.stringify(j,null,2);}catch{}setTimeout(tick,1500);}
loadProjects();tick();
</script></main></body></html>
""";
}