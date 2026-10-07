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
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>Ronin Forge Supervisor</title>
<style>
body{font-family:Segoe UI,Arial;background:#0d0f12;color:#eee;margin:0}
header{padding:24px 32px;background:#14171c;border-bottom:1px solid #2b3038}
main{max-width:1100px;margin:auto;padding:24px}
.card{background:#15191f;border:1px solid #2d333d;border-radius:14px;padding:18px;margin:14px 0}
input,select,button{background:#0f1216;color:#eee;border:1px solid #3a414d;border-radius:8px;padding:10px;margin:5px}
input{min-width:290px}button{cursor:pointer;background:#c79a43;color:#111;font-weight:700}
.secondary{background:#252a32;color:#eee}
pre{white-space:pre-wrap;max-height:360px;overflow:auto;background:#090b0e;padding:12px;border-radius:8px}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
small{color:#aab2bd}.project-row{padding:6px 0}
</style>
</head>
<body>
<header><h1>Ronin Forge Supervisor</h1><div>Autonomous compile → diagnose → repair → rebuild</div></header>
<main>
<div class="card">
<h2>1. Provider + worker</h2>
<div class="row">
<input id="pname" value="OpenRouter" placeholder="Provider">
<input id="base" value="https://openrouter.ai/api/v1" placeholder="Base URL">
<input id="model" value="qwen/qwen3-coder-next" placeholder="Model">
</div>
<div class="row">
<input id="pkey" type="password" placeholder="Provider API key">
<input id="repo" value="RoninMerc/RonisOS-BPlus" placeholder="GitHub worker repo">
<input id="branch" value="vanta-forge-worker" placeholder="Worker branch">
<input id="gtoken" type="password" placeholder="GitHub token">
</div>
<div class="row">
<select id="platform"><option value="android">Android APK</option><option value="windows">Windows</option></select>
<label>Max cycles <input id="max" type="number" value="50" min="1" max="500" style="min-width:80px"></label>
<button id="saveBtn" type="button">Save settings</button>
</div>
<small>Keys are protected with Windows DPAPI for the current Windows user.</small>
</div>

<div class="card">
<h2>2. Import project</h2>
<div class="row">
<input id="projectName" placeholder="Project name">
<input id="zip" type="file" accept=".zip">
<button id="importBtn" type="button">Import Vanta/source ZIP</button>
</div>
<div id="projects"></div>
</div>

<div class="card">
<h2>3. Supervisor</h2>
<div class="row">
<button id="runBtn" type="button">Run until success</button>
<button id="pauseBtn" class="secondary" type="button">Pause</button>
<button id="stopBtn" class="secondary" type="button">Stop</button>
</div>
<pre id="state">Starting dashboard...</pre>
</div>
</main>

<script>
(function(){
  "use strict";

  let current = "";
  const byId = function(id){ return document.getElementById(id); };

  async function api(url, options){
    const response = await fetch(url, options || {});
    if(!response.ok){
      const text = await response.text();
      throw new Error("HTTP " + response.status + (text ? ": " + text : ""));
    }
    return response;
  }

  async function loadSaved(){
    const response = await api("/api/settings", {cache:"no-store"});
    const j = await response.json();
    byId("pname").value = j.providerName || "OpenRouter";
    byId("base").value = j.providerBaseUrl || "https://openrouter.ai/api/v1";
    byId("model").value = j.model || "qwen/qwen3-coder-next";
    byId("repo").value = j.githubRepo || "RoninMerc/RonisOS-BPlus";
    byId("branch").value = j.githubBranch || "vanta-forge-worker";
    byId("platform").value = j.platform || "android";
    byId("max").value = j.maxCycles || 50;
    if(j.providerKeySaved) byId("pkey").placeholder = "Provider key saved";
    if(j.githubTokenSaved) byId("gtoken").placeholder = "GitHub token saved";
  }

  async function saveSettings(){
    const payload = {
      providerName: byId("pname").value,
      providerBaseUrl: byId("base").value,
      providerApiKey: byId("pkey").value,
      model: byId("model").value,
      githubRepo: byId("repo").value,
      githubBranch: byId("branch").value,
      githubToken: byId("gtoken").value,
      platform: byId("platform").value,
      maxCycles: Number(byId("max").value || 50)
    };
    await api("/api/settings", {
      method:"POST",
      headers:{"content-type":"application/json"},
      body:JSON.stringify(payload)
    });
    alert("Settings saved");
    await loadSaved();
  }

  async function loadProjects(){
    const response = await api("/api/projects", {cache:"no-store"});
    const list = await response.json();
    const holder = byId("projects");
    holder.textContent = "";

    list.forEach(function(project){
      const row = document.createElement("div");
      row.className = "project-row";

      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "project";
      radio.value = project.id;
      radio.checked = current === project.id;
      radio.addEventListener("change", function(){ current = project.id; });

      const label = document.createElement("span");
      label.textContent = " " + project.name + " (" + project.files.length + " files)";

      row.appendChild(radio);
      row.appendChild(label);
      holder.appendChild(row);
    });

    if(!current && list.length){
      current = list[list.length - 1].id;
      const radios = holder.querySelectorAll('input[type="radio"]');
      if(radios.length) radios[radios.length - 1].checked = true;
    }
  }

  async function importProject(){
    const file = byId("zip").files[0];
    if(!file){ alert("Select a ZIP first."); return; }

    const data = new FormData();
    data.append("file", file);
    data.append("name", byId("projectName").value || file.name);

    const response = await api("/api/project/import", {method:"POST", body:data});
    const result = await response.json();
    current = result.id;
    await loadProjects();
  }

  async function runSupervisor(){
    if(!current){ alert("Import or select a project first."); return; }
    await api("/api/run/start/" + encodeURIComponent(current), {method:"POST"});
  }

  async function pauseSupervisor(){ await api("/api/run/pause", {method:"POST"}); }
  async function stopSupervisor(){ await api("/api/run/stop", {method:"POST"}); }

  async function poll(){
    try{
      const response = await api("/api/state", {cache:"no-store"});
      const value = await response.json();
      byId("state").textContent = JSON.stringify(value, null, 2);
    }catch(error){
      byId("state").textContent = "Supervisor API error: " + error.message;
    }
    window.setTimeout(poll, 1500);
  }

  window.addEventListener("DOMContentLoaded", async function(){
    byId("saveBtn").addEventListener("click", function(){ saveSettings().catch(function(e){ alert(e.message); }); });
    byId("importBtn").addEventListener("click", function(){ importProject().catch(function(e){ alert(e.message); }); });
    byId("runBtn").addEventListener("click", function(){ runSupervisor().catch(function(e){ alert(e.message); }); });
    byId("pauseBtn").addEventListener("click", function(){ pauseSupervisor().catch(function(e){ alert(e.message); }); });
    byId("stopBtn").addEventListener("click", function(){ stopSupervisor().catch(function(e){ alert(e.message); }); });

    try{
      await loadSaved();
      await loadProjects();
    }catch(error){
      byId("state").textContent = "Dashboard startup error: " + error.message;
    }
    poll();
  });
})();
</script>
</body>
</html>
""";
}
