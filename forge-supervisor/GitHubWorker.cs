using System.IO.Compression;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

sealed class GitHubWorker
{
    readonly SettingsInput cfg;
    readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(30) };

    public GitHubWorker(SettingsInput c)
    {
        cfg = c;
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", c.GithubToken);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("RoninForgeSupervisor/0.1");
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
    }

    (string owner, string repo) Parts()
    {
        var x = cfg.GithubRepo.Split('/');
        if (x.Length != 2) throw new Exception("GitHub repo must be owner/repo");
        return (x[0], x[1]);
    }

    async Task<JsonNode> Json(HttpMethod method, string url, JsonNode? body, CancellationToken token)
    {
        using var req = new HttpRequestMessage(method, url);
        if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var res = await http.SendAsync(req, token);
        var raw = await res.Content.ReadAsStringAsync(token);
        if (!res.IsSuccessStatusCode) throw new Exception("GitHub " + (int)res.StatusCode + ": " + raw);
        return JsonNode.Parse(raw) ?? new JsonObject();
    }

    public async Task<BuildResult> RunProject(ProjectInfo project, int cycle, string appRoot, CancellationToken token)
    {
        var requestId = "supervisor-" + project.Id[..8] + "-" + cycle + "-" + DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var files = new JsonArray();

        foreach (var path in Directory.GetFiles(project.Root, "*", SearchOption.AllDirectories))
        {
            if (SupervisorEngine.IsBinary(path)) continue;
            var rel = Path.GetRelativePath(project.Root, path).Replace('\\', '/');
            files.Add(new JsonObject
            {
                ["path"] = rel,
                ["content"] = await File.ReadAllTextAsync(path, token)
            });
        }

        var request = new JsonObject
        {
            ["request_id"] = requestId,
            ["attempt"] = cycle,
            ["build_type"] = "release",
            ["project"] = new JsonObject
            {
                ["name"] = project.Name,
                ["files"] = files
            }
        };

        return await Run(requestId, request, project.Id, cycle, appRoot, token);
    }

    async Task<BuildResult> Run(string requestId, JsonObject request, string projectId, int cycle, string appRoot, CancellationToken token)
    {
        var (owner, repo) = Parts();
        var path = "vanta-forge/requests/" + requestId + ".json";
        var payload = new JsonObject
        {
            ["message"] = "Supervisor build " + requestId,
            ["branch"] = cfg.GithubBranch,
            ["content"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(request.ToJsonString()))
        };

        var created = await Json(HttpMethod.Put, "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path, payload, token);
        var sha = created["commit"]?["sha"]?.GetValue<string>() ?? "";

        long runId = 0;
        for (int i = 0; i < 60 && runId == 0; i++)
        {
            await Task.Delay(3000, token);
            var runs = await Json(HttpMethod.Get,
                "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs?branch=" + Uri.EscapeDataString(cfg.GithubBranch) + "&event=push&per_page=20",
                null, token);

            foreach (var r in runs["workflow_runs"]?.AsArray() ?? [])
            {
                if (r?["head_sha"]?.GetValue<string>() == sha)
                {
                    runId = r!["id"]!.GetValue<long>();
                    break;
                }
            }
        }

        if (runId == 0) throw new Exception("Worker workflow did not start.");

        string conclusion = "";
        for (int i = 0; i < 240; i++)
        {
            var run = await Json(HttpMethod.Get, "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + runId, null, token);
            var status = run["status"]?.GetValue<string>() ?? "";
            conclusion = run["conclusion"]?.GetValue<string>() ?? "";
            if (status == "completed") break;
            await Task.Delay(5000, token);
        }

        var arts = await Json(HttpMethod.Get, "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/artifacts", null, token);
        var arr = arts["artifacts"]?.AsArray() ?? [];
        var logArt = arr.FirstOrDefault(a => a?["name"]?.GetValue<string>() == "vanta-forge-log-" + requestId);
        var apkArt = arr.FirstOrDefault(a => a?["name"]?.GetValue<string>() == "vanta-forge-apk-" + requestId);

        string log = "";
        if (logArt != null)
        {
            var zip = await DownloadArtifact(owner, repo, logArt["id"]!.GetValue<long>(), token);
            using var z = ZipFile.OpenRead(zip);
            var entry = z.Entries.FirstOrDefault();
            if (entry != null)
            {
                using var sr = new StreamReader(entry.Open());
                log = await sr.ReadToEndAsync(token);
            }
        }

        if (conclusion == "success" && apkArt != null)
        {
            var zip = await DownloadArtifact(owner, repo, apkArt["id"]!.GetValue<long>(), token);
            var outDir = Path.Combine(appRoot, "artifacts", projectId, "cycle-" + cycle);
            Directory.CreateDirectory(outDir);

            using var z = ZipFile.OpenRead(zip);
            var apk = z.Entries.FirstOrDefault(e => e.Name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase));
            if (apk == null) return new(false, log + "\nWorker reported success but APK artifact was empty.", "");

            var outPath = Path.Combine(outDir, "app-built.apk");
            apk.ExtractToFile(outPath, true);
            return new(true, log, outPath);
        }

        return new(false, log.Length > 0 ? log : "Worker failed: " + conclusion, "");
    }

    async Task<string> DownloadArtifact(string owner, string repo, long id, CancellationToken token)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com/repos/" + owner + "/" + repo + "/actions/artifacts/" + id + "/zip");
        using var res = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, token);
        if (!res.IsSuccessStatusCode) throw new Exception("Artifact download failed: " + res.StatusCode);

        var temp = Path.Combine(Path.GetTempPath(), "rfs-" + id + ".zip");
        await using var fs = File.Create(temp);
        await res.Content.CopyToAsync(fs, token);
        return temp;
    }
}