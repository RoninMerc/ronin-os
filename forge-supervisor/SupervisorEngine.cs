using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

sealed class SupervisorEngine
{
    readonly AppStore store;
    CancellationTokenSource? cts;
    public SupervisorState State { get; } = new();\n    public bool IsBusy => cts is not null && !cts.IsCancellationRequested;

    public SupervisorEngine(AppStore s) { store = s; }

    public bool Start(string projectId)
    {
        if (cts is not null && !cts.IsCancellationRequested) return false;
        var p = store.GetProject(projectId);
        cts = new();
        _ = Task.Run(() => Loop(p, cts.Token));
        return true;
    }

    public void Pause() { cts?.Cancel(); Set("PAUSED", "Paused by user"); }
    public void Stop() { cts?.Cancel(); Set("STOPPED", "Stopped by user"); }

    public async Task<ProjectInfo?> TakeOverLatestFailedVanta(bool startImmediately, CancellationToken token)
    {
        if (IsBusy) return null;
        var cfg = store.EffectiveSettings();
        if (string.IsNullOrWhiteSpace(cfg.GithubToken))
            throw new InvalidOperationException("GitHub worker token is not configured.");

        var snapshot = await new VantaInbox(cfg).LatestFailedRequest(store, token);
        if (snapshot == null) return null;

        var project = store.ImportVantaSnapshot(snapshot);
        State.ProjectId = project.Id;
        State.ProjectName = project.Name;
        Set("TAKEOVER", "Imported failed Vanta task " + snapshot.RequestId + " directly from the worker. No ZIP or copied diagnostics required.", "VANTA_INBOX");
        if (startImmediately) Start(project.Id);
        return project;
    }

    public void WatchNote(string message)
    {
        if (!IsBusy && State.Status is "IDLE" or "WATCHING")
            Set("WATCHING", message, "VANTA_WATCH");
    }

    void Set(string status, string msg, string stage = "")
    {
        lock (State)
        {
            State.Status = status;
            State.Message = msg;
            if (stage.Length > 0) State.Stage = stage;
            State.Updated = DateTimeOffset.UtcNow;
            State.Recent.Insert(0, DateTime.Now.ToString("HH:mm:ss") + " " + status + ": " + msg);
            if (State.Recent.Count > 60) State.Recent.RemoveRange(60, State.Recent.Count - 60);
        }
    }

    async Task Loop(ProjectInfo p, CancellationToken token)
    {
        State.ProjectId = p.Id; State.ProjectName = p.Name; State.Cycle = 0; State.Artifact = "";
        Set("RUNNING", "Supervisor started", "PREFLIGHT");
        try
        {
            var cfg = store.EffectiveSettings();
            if (string.IsNullOrWhiteSpace(cfg.ProviderApiKey)) throw new Exception("Provider API key is not configured.");
            if (cfg.Platform == "android" && string.IsNullOrWhiteSpace(cfg.GithubToken)) throw new Exception("GitHub worker token is not configured.");

            for (int cycle = 1; cycle <= cfg.MaxCycles; cycle++)
            {
                token.ThrowIfCancellationRequested();
                State.Cycle = cycle;
                string sourceHash = HashProject(p.Root);
                Set("RUNNING", "Build cycle " + cycle, "BUILD");

                BuildResult br = cfg.Platform.Equals("windows", StringComparison.OrdinalIgnoreCase)
                    ? await BuildWindows(p, cycle, token)
                    : await new GitHubWorker(cfg).RunProject(p, cycle, store.Root, token);

                if (br.Success)
                {
                    State.Artifact = br.Artifact;
                    store.AppendLedger(p.Id, new(cycle, DateTimeOffset.UtcNow, sourceHash, "", "SUCCESS", "Build/tests passed", [], "SUCCESS"));
                    Set("SUCCESS", "Build, tests and artifact verification passed.", "COMPLETE");
                    return;
                }

                State.LastError = br.Log;
                var classification = Classify(br.Log);
                var errorSignature = ErrorSignature(br.Log);
                var errorHash = Sha(errorSignature);
                var ledger = store.LoadLedger(p.Id);
                var sameExact = ledger.Count(x => x.SourceHash == sourceHash && x.ErrorHash == errorHash);
                var sameSemantic = ledger.Count(x => x.ErrorHash == errorHash);
                if (sameExact >= 2)
                {
                    Set("BLOCKED", "Same source and same compiler failure repeated twice. Repair loop stopped to prevent wasted builds.", "CIRCUIT_BREAKER");
                    return;
                }
                if (sameSemantic >= 6)
                {
                    Set("BLOCKED", "The same underlying compiler failure survived six repair cycles. Automatic churn stopped; source and ledger are retained.", "CIRCUIT_BREAKER");
                    return;
                }

                Set("RUNNING", classification + ": requesting targeted repair", "REPAIR");
                RepairResponse repair = new();
                ApplyResult applied = new([], []);
                string rejectionContext = "";
                for (int repairAttempt = 1; repairAttempt <= 3; repairAttempt++)
                {
                    repair = await AskRepair(p, cfg, br.Log + rejectionContext, classification, token);
                    if (repair.Files.Count == 0)
                    {
                        rejectionContext = "\nPREVIOUS REPAIR RESPONSE CONTAINED NO FILES. Return implementation files only.";
                        continue;
                    }

                    applied = ApplyRepair(p, repair);
                    if (applied.ChangedFiles.Count > 0) break;

                    rejectionContext = "\nPREVIOUS REPAIR WAS REJECTED BY THE SUPERVISOR. " +
                        "Rejected paths: " + string.Join(", ", applied.RejectedFiles) + ". " +
                        "Do not modify tests, androidTest, .git, or unsafe paths. Repair production implementation/configuration files instead.";
                }

                if (applied.ChangedFiles.Count == 0)
                {
                    Set("BLOCKED", "Repair attempts only proposed unsafe/protected changes. Source and tests were preserved.", "REPAIR_GUARD");
                    return;
                }

                store.AppendLedger(p.Id, new(cycle, DateTimeOffset.UtcNow, sourceHash, errorHash, classification, repair.Summary, applied.ChangedFiles, "REPAIRED"));
            }
            Set("BLOCKED", "Maximum configured build cycles reached.", "LIMIT");
        }
        catch (OperationCanceledException)
        {
            if (State.Status != "STOPPED") Set("PAUSED", "Supervisor paused; source and ledger retained.");
        }
        catch (Exception ex)
        {
            State.LastError = ex.ToString();
            Set("BLOCKED", ex.Message, "ERROR");
        }
        finally { cts = null; }
    }

    static string Classify(string log)
    {
        if (log.Contains("FileAlreadyExistsException", StringComparison.OrdinalIgnoreCase) &&
            Regex.IsMatch(log, @"[A-Za-z_][A-Za-z0-9_]*_Impl\.java", RegexOptions.IgnoreCase))
            return "DUPLICATE_GENERATED_TYPE";
        if (log.Contains("capacity_exhausted", StringComparison.OrdinalIgnoreCase)) return "MODEL_CAPACITY";
        if (log.Contains("resource linking failed", StringComparison.OrdinalIgnoreCase)) return "DEPENDENCY_OR_RESOURCE";
        if (log.Contains("kapt", StringComparison.OrdinalIgnoreCase)) return "KOTLIN_ANNOTATION_PROCESSING";
        if (log.Contains("Unresolved reference", StringComparison.OrdinalIgnoreCase)) return "SOURCE_API_MISMATCH";
        if (log.Contains("FAILED", StringComparison.OrdinalIgnoreCase) && log.Contains("test", StringComparison.OrdinalIgnoreCase)) return "TEST_FAILURE";
        if (log.Contains("lint", StringComparison.OrdinalIgnoreCase)) return "LINT_FAILURE";
        return "COMPILER_FAILURE";
    }

    public static bool IsBinary(string p) =>
        new[] { ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".keystore", ".jks", ".apk", ".exe", ".dll", ".so", ".jar", ".zip" }
            .Contains(Path.GetExtension(p).ToLowerInvariant());

    static string HashProject(string root)
    {
        var sb = new StringBuilder();
        foreach (var f in Directory.GetFiles(root, "*", SearchOption.AllDirectories).OrderBy(x => x))
        {
            if (IsBinary(f)) continue;
            sb.Append(Path.GetRelativePath(root, f).Replace('\\', '/')).Append('\0').Append(Sha(File.ReadAllText(f))).Append('\n');
        }
        return Sha(sb.ToString());
    }

    static string Sha(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s))).ToLowerInvariant();

    static string ErrorSignature(string log)
    {
        var lines = log.Replace("\r", "").Split('\n');
        var selected = lines
            .Select(x => x.Trim())
            .Where(x => x.Length > 0)
            .Where(x =>
                x.Contains("error:", StringComparison.OrdinalIgnoreCase) ||
                x.StartsWith("e:", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("FAILED", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("Execution failed for task", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("Unresolved reference", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("cannot find", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("not found", StringComparison.OrdinalIgnoreCase) ||
                x.Contains("What went wrong", StringComparison.OrdinalIgnoreCase))
            .Select(x => Regex.Replace(x, @"\b\d+(?:\.\d+)?(?:ms|s|m)?\b", "#"))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(100)
            .ToList();
        if (selected.Count == 0)
            selected = lines.Select(x => x.Trim()).Where(x => x.Length > 0).TakeLast(80).ToList();
        return string.Join("\n", selected);
    }

    async Task<BuildResult> BuildWindows(ProjectInfo p, int cycle, CancellationToken token)
    {
        var log = new StringBuilder();
        var psi = new ProcessStartInfo("dotnet", "test --configuration Release --nologo")
        {
            WorkingDirectory = p.Root, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false
        };
        using var proc = Process.Start(psi)!;
        log.Append(await proc.StandardOutput.ReadToEndAsync(token));
        log.Append(await proc.StandardError.ReadToEndAsync(token));
        await proc.WaitForExitAsync(token);
        if (proc.ExitCode != 0) return new(false, log.ToString(), "");

        var outDir = Path.Combine(store.Root, "artifacts", p.Id, "cycle-" + cycle);
        Directory.CreateDirectory(outDir);
        psi = new ProcessStartInfo("dotnet", "publish --configuration Release --nologo -o \"" + outDir + "\"")
        {
            WorkingDirectory = p.Root, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false
        };
        using var pub = Process.Start(psi)!;
        log.Append(await pub.StandardOutput.ReadToEndAsync(token));
        log.Append(await pub.StandardError.ReadToEndAsync(token));
        await pub.WaitForExitAsync(token);
        var exe = Directory.GetFiles(outDir, "*.exe", SearchOption.TopDirectoryOnly).FirstOrDefault() ?? "";
        return new(pub.ExitCode == 0 && exe.Length > 0, log.ToString(), exe);
    }

    async Task<RepairResponse> AskRepair(ProjectInfo p, SettingsInput cfg, string diagnostics, string classification, CancellationToken token)
    {
        var relevant = RelevantFiles(p.Root, diagnostics);
        var source = new StringBuilder();
        foreach (var f in relevant)
        {
            var text = await File.ReadAllTextAsync(f, token);
            source.Append("\n--- FILE: ").Append(Path.GetRelativePath(p.Root, f).Replace('\\', '/')).Append(" ---\n");
            source.Append(text[..Math.Min(text.Length, 18000)]);
        }

        var diag = diagnostics[..Math.Min(diagnostics.Length, 30000)];
        var basePrompt = "You are the coding repair engine for Ronin Forge Supervisor.\n" +
                         "Classification: " + classification + "\n" +
                         "Repair the implementation without weakening verification.\n" +
                         "IMMUTABLE RULES:\n" +
                         "- Do not delete, skip, disable, rename away, or weaken tests.\n" +
                         "- Do not set ignoreFailures=true or disable lint/build gates.\n" +
                         "- Do not remove requested functionality merely to compile.\n" +
                         "- Prefer the smallest coherent repair.\n" +
                         "- Return one JSON object only. No prose before or after it.\n" +
                         "Expected shape: {\"summary\":\"...\",\"files\":[{\"path\":\"relative/path\",\"content\":\"COMPLETE FILE\"}]}\n" +
                         "BUILD DIAGNOSTICS:\n" + diag + "\nRELEVANT SOURCE:\n" + source;

        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", cfg.ProviderApiKey);
        var endpoint = cfg.ProviderBaseUrl.TrimEnd('/') + "/chat/completions";

        string lastProblem = "No provider attempt was made.";
        for (int attempt = 1; attempt <= 3; attempt++)
        {
            token.ThrowIfCancellationRequested();
            var prompt = basePrompt + (attempt == 1 ? "" :
                "\nPREVIOUS RESPONSE WAS NOT A VALID REPAIR JSON OBJECT. Return the required JSON object directly now. Do not emit reasoning, markdown, commentary, or an empty answer.");

            var req = new JsonObject
            {
                ["model"] = cfg.Model,
                ["temperature"] = 0.05,
                ["max_tokens"] = 16384,
                ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = prompt })
            };

            if (cfg.ProviderName.Equals("Featherless", StringComparison.OrdinalIgnoreCase) ||
                cfg.ProviderBaseUrl.Contains("featherless.ai", StringComparison.OrdinalIgnoreCase))
                req["chat_template_kwargs"] = new JsonObject { ["enable_thinking"] = false };

            using var resp = await http.PostAsync(endpoint, new StringContent(req.ToJsonString(), Encoding.UTF8, "application/json"), token);
            var raw = await resp.Content.ReadAsStringAsync(token);

            if (!resp.IsSuccessStatusCode)
            {
                lastProblem = "Provider HTTP " + (int)resp.StatusCode + ": " + raw[..Math.Min(raw.Length, 4000)];
                if (((int)resp.StatusCode == 429 || (int)resp.StatusCode >= 500) && attempt < 3)
                {
                    await Task.Delay(TimeSpan.FromSeconds(attempt * 20), token);
                    continue;
                }
                throw new Exception(lastProblem);
            }

            JsonNode json;
            try { json = JsonNode.Parse(raw) ?? throw new JsonException("Empty provider envelope"); }
            catch (Exception ex)
            {
                lastProblem = "Provider returned invalid JSON envelope: " + ex.Message;
                if (attempt < 3) { await Task.Delay(TimeSpan.FromSeconds(5), token); continue; }
                throw new Exception(lastProblem);
            }

            var message = json["choices"]?[0]?["message"];
            var answer = message?["content"]?.GetValue<string>() ?? "";
            var finishReason = json["choices"]?[0]?["finish_reason"]?.GetValue<string>() ?? "";
            int reasoningChars = 0;
            try
            {
                var reasoning = message?["reasoning"]?.GetValue<string>() ?? message?["reasoning_content"]?.GetValue<string>() ?? "";
                reasoningChars = reasoning.Length;
            }
            catch { }

            if (string.IsNullOrWhiteSpace(answer))
            {
                lastProblem = "Provider returned no usable answer content" +
                    (reasoningChars > 0 ? " (" + reasoningChars + " reasoning characters, zero source-answer characters)" : "") +
                    (finishReason.Length > 0 ? "; finish_reason=" + finishReason : "") + ".";
                if (attempt < 3) { await Task.Delay(TimeSpan.FromSeconds(5), token); continue; }
                throw new Exception(lastProblem);
            }

            if (TryParseRepair(answer, out var repair, out var parseProblem))
            {
                if (repair.Files.Count > 0) return repair;
                lastProblem = "Provider returned valid JSON but no repair files.";
            }
            else lastProblem = "Provider answer was not valid repair JSON: " + parseProblem;

            if (attempt < 3) { await Task.Delay(TimeSpan.FromSeconds(5), token); continue; }
        }
        throw new Exception(lastProblem);
    }

    static bool TryParseRepair(string answer, out RepairResponse repair, out string problem)
    {
        repair = new RepairResponse();
        problem = "";
        var cleaned = answer.Trim();
        var ticks = new string(char.ConvertFromUtf32(96)[0], 3);
        if (cleaned.StartsWith(ticks, StringComparison.Ordinal))
        {
            cleaned = cleaned[ticks.Length..];
            if (cleaned.StartsWith("json", StringComparison.OrdinalIgnoreCase))
                cleaned = cleaned[4..];
            cleaned = cleaned.TrimStart();
            if (cleaned.EndsWith(ticks, StringComparison.Ordinal))
                cleaned = cleaned[..^ticks.Length];
            cleaned = cleaned.Trim();
        }

        foreach (var candidate in JsonCandidates(cleaned))
        {
            try
            {
                var parsed = JsonSerializer.Deserialize<RepairResponse>(candidate, JsonOpts.Options);
                if (parsed != null && (parsed.Files.Count > 0 || parsed.Summary.Length > 0))
                {
                    repair = parsed;
                    return true;
                }
            }
            catch (JsonException ex) { problem = ex.Message; }
        }

        if (problem.Length == 0)
            problem = cleaned.Length == 0 ? "empty content" : "no complete JSON object found in provider content";
        return false;
    }

    static IEnumerable<string> JsonCandidates(string text)
    {
        if (text.Length == 0) yield break;
        if (text[0] == '{' && text[^1] == '}') yield return text;

        bool inString = false, escape = false;
        int depth = 0, start = -1;
        for (int i = 0; i < text.Length; i++)
        {
            char c = text[i];
            if (inString)
            {
                if (escape) { escape = false; continue; }
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{')
            {
                if (depth == 0) start = i;
                depth++;
            }
            else if (c == '}' && depth > 0)
            {
                depth--;
                if (depth == 0 && start >= 0)
                {
                    yield return text[start..(i + 1)];
                    start = -1;
                }
            }
        }
    }

    static List<string> RelevantFiles(string root, string diagnostics)
    {
        var all = Directory.GetFiles(root, "*", SearchOption.AllDirectories).Where(x => !IsBinary(x)).ToList();
        var result = all.Where(f =>
            diagnostics.Contains(Path.GetFileName(f), StringComparison.OrdinalIgnoreCase) ||
            diagnostics.Contains(Path.GetRelativePath(root, f).Replace('\\', '/'), StringComparison.OrdinalIgnoreCase)).ToList();

        foreach (var f in all.Where(f => Regex.IsMatch(Path.GetFileName(f), @"^(build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?|AndroidManifest\.xml)$", RegexOptions.IgnoreCase)))
            if (!result.Contains(f)) result.Add(f);

        return result.Take(18).ToList();
    }

    static ApplyResult ApplyRepair(ProjectInfo p, RepairResponse repair)
    {
        var changed = new List<string>();
        var rejected = new List<string>();
        foreach (var item in repair.Files)
        {
            var rel = item.Path.Replace('\\', '/').TrimStart('/');
            bool blockedPath = rel.Contains("..") || rel.StartsWith(".git/", StringComparison.OrdinalIgnoreCase) ||
                          rel.Contains("/src/test/", StringComparison.OrdinalIgnoreCase) ||
                          rel.Contains("/src/androidTest/", StringComparison.OrdinalIgnoreCase) ||
                          rel.StartsWith("src/test/", StringComparison.OrdinalIgnoreCase) ||
                          rel.StartsWith("src/androidTest/", StringComparison.OrdinalIgnoreCase);
            if (blockedPath) { rejected.Add(rel); continue; }

            var dest = Path.GetFullPath(Path.Combine(p.Root, rel.Replace('/', Path.DirectorySeparatorChar)));
            var prefix = Path.GetFullPath(p.Root) + Path.DirectorySeparatorChar;
            if (!dest.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                rejected.Add(rel);
                continue;
            }

            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            var before = File.Exists(dest) ? File.ReadAllText(dest) : null;
            if (before == item.Content)
            {
                rejected.Add(rel + " [unchanged]");
                continue;
            }

            File.WriteAllText(dest, item.Content);
            changed.Add(rel);
        }
        return new(changed, rejected);
    }
}
