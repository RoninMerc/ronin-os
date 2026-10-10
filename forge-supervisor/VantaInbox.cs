using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

sealed class VantaInbox
{
    readonly SettingsInput cfg;
    readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(2) };

    public VantaInbox(SettingsInput settings)
    {
        cfg = settings;
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", settings.GithubToken);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("RoninForgeSupervisor/0.2");
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
    }

    (string owner, string repo) Parts()
    {
        var pieces = cfg.GithubRepo.Split('/');
        if (pieces.Length != 2) throw new InvalidOperationException("GitHub repo must be owner/repo.");
        return (pieces[0], pieces[1]);
    }

    async Task<JsonNode> Get(string url, CancellationToken token)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        using var res = await http.SendAsync(req, token);
        var raw = await res.Content.ReadAsStringAsync(token);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException("GitHub " + (int)res.StatusCode + ": " + raw);
        return JsonNode.Parse(raw) ?? new JsonObject();
    }

    public async Task<VantaRequestSnapshot?> LatestFailedRequest(AppStore store, CancellationToken token)
    {
        var (owner, repo) = Parts();
        var commits = await Get(
            "https://api.github.com/repos/" + owner + "/" + repo +
            "/commits?sha=" + Uri.EscapeDataString(cfg.GithubBranch) +
            "&path=" + Uri.EscapeDataString("vanta-forge/requests") +
            "&per_page=40", token);

        foreach (var commit in commits.AsArray())
        {
            token.ThrowIfCancellationRequested();
            var sha = commit?["sha"]?.GetValue<string>() ?? "";
            if (sha.Length == 0) continue;

            var detail = await Get("https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + sha, token);
            var changed = detail["files"]?.AsArray() ?? [];

            foreach (var file in changed)
            {
                var path = file?["filename"]?.GetValue<string>() ?? "";
                var name = Path.GetFileName(path);
                if (!path.StartsWith("vanta-forge/requests/", StringComparison.Ordinal) ||
                    !name.StartsWith("forge-", StringComparison.Ordinal) ||
                    !name.EndsWith(".json", StringComparison.OrdinalIgnoreCase) ||
                    name.StartsWith("forge-supervisor-", StringComparison.OrdinalIgnoreCase))
                    continue;

                var requestId = Path.GetFileNameWithoutExtension(name);
                if (store.HasSeenVantaRequest(requestId)) continue;

                var runs = await Get(
                    "https://api.github.com/repos/" + owner + "/" + repo +
                    "/actions/runs?head_sha=" + Uri.EscapeDataString(sha) + "&per_page=20", token);

                bool failed = false;
                foreach (var run in runs["workflow_runs"]?.AsArray() ?? [])
                {
                    var status = run?["status"]?.GetValue<string>() ?? "";
                    var conclusion = run?["conclusion"]?.GetValue<string>() ?? "";
                    var workflowName = run?["name"]?.GetValue<string>() ?? "";
                    if (status == "completed" &&
                        conclusion == "failure" &&
                        workflowName.Contains("Vanta Forge", StringComparison.OrdinalIgnoreCase))
                    {
                        failed = true;
                        break;
                    }
                }
                if (!failed) continue;

                var encoded = await Get(
                    "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" +
                    path.Split('/').Select(Uri.EscapeDataString).Aggregate((a, b) => a + "/" + b) +
                    "?ref=" + Uri.EscapeDataString(sha), token);

                var b64 = encoded["content"]?.GetValue<string>()?.Replace("\n", "") ?? "";
                if (b64.Length == 0) continue;

                var raw = Encoding.UTF8.GetString(Convert.FromBase64String(b64));
                var request = JsonNode.Parse(raw) as JsonObject;
                if (request == null) continue;

                var project = request["project"] as JsonObject;
                var files = project?["files"]?.AsArray();
                if (project == null || files == null || files.Count == 0) continue;

                var snapshotFiles = new List<VantaSourceFile>();
                foreach (var item in files)
                {
                    if (item is not JsonObject obj) continue;
                    var sourcePath = obj["path"]?.GetValue<string>() ?? "";
                    if (sourcePath.Length == 0) continue;
                    var encoding = obj["encoding"]?.GetValue<string>() ?? "utf-8";
                    var content = obj["content"]?.GetValue<string>() ?? "";
                    byte[] bytes;
                    bool text;
                    if (encoding.Equals("base64", StringComparison.OrdinalIgnoreCase))
                    {
                        try { bytes = Convert.FromBase64String(content); }
                        catch { continue; }
                        text = false;
                    }
                    else
                    {
                        bytes = Encoding.UTF8.GetBytes(content);
                        text = true;
                    }
                    snapshotFiles.Add(new VantaSourceFile(sourcePath, bytes, text));
                }

                if (snapshotFiles.Count == 0) continue;
                var projectName = project["name"]?.GetValue<string>() ?? "Vanta Android project";
                return new VantaRequestSnapshot(
                    requestId,
                    projectName,
                    sha,
                    DateTimeOffset.UtcNow,
                    snapshotFiles);
            }
        }
        return null;
    }
}
