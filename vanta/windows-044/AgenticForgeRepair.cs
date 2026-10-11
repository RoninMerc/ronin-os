using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Vanta.Core;

public static class ForgeAgenticRepair
{
    public const int MaxFilesPerPass = 3;

    public static bool TestPath(string path)
        => Regex.IsMatch(path.Replace('\\','/'), @"/src/(?:test[^/]*|androidTest[^/]*)/", RegexOptions.IgnoreCase)
           || Regex.IsMatch(path.Replace('\\','/'), @"(?:^|/)(?:tests?|specs?)(?:/|$)", RegexOptions.IgnoreCase);

    public static List<string> Targets(ProjectRecord project, string diagnostics)
    {
        var files = project.Files
            .Where(f => f.Encoding == "utf-8" && !AndroidForgePreparation.GateName(f.Path))
            .ToDictionary(f => f.Path, StringComparer.OrdinalIgnoreCase);

        var hits = new List<(string path,int score,int count)>();
        foreach (var file in files.Values)
        {
            if (TestPath(file.Path)) continue;
            int count = 0;
            int score = 100;
            foreach (var line in diagnostics.Split('\n'))
            {
                if (!line.Contains(file.Path, StringComparison.OrdinalIgnoreCase)
                    && !line.Contains(Path.GetFileName(file.Path) + ":", StringComparison.OrdinalIgnoreCase)
                    && !line.Contains(Path.GetFileName(file.Path) + "(", StringComparison.OrdinalIgnoreCase))
                    continue;
                count++;
                string lower = line.ToLowerInvariant();
                if (lower.Contains("expecting") || lower.Contains("syntax") || lower.Contains("parse")) score -= 80;
                if (lower.Contains("redeclaration") || lower.Contains("conflicting") || lower.Contains("already defined")) score -= 70;
                if (lower.Contains("cannot be inherited") || lower.Contains("overrides nothing") || lower.Contains("annotation argument")) score -= 50;
                if (lower.Contains("unresolved reference") || lower.Contains("cannot find") || lower.Contains("not found")) score -= 30;
            }
            if (count > 0) hits.Add((file.Path, score, count));
        }

        return hits
            .OrderBy(x => x.score)
            .ThenByDescending(x => x.count)
            .ThenBy(x => x.path, StringComparer.OrdinalIgnoreCase)
            .Select(x => x.path)
            .Take(MaxFilesPerPass)
            .ToList();
    }

    public static string DiagnosticSignature(string log)
    {
        var lines = log.Split('\n')
            .Select(x => x.Trim())
            .Where(x => x.Length > 0
                && (x.Contains("error", StringComparison.OrdinalIgnoreCase)
                    || x.Contains("failed", StringComparison.OrdinalIgnoreCase)
                    || x.Contains("unresolved", StringComparison.OrdinalIgnoreCase)
                    || x.Contains("exception", StringComparison.OrdinalIgnoreCase)))
            .Select(x => Regex.Replace(x, @"\b\d+(?:ms|s|m)?\b", "#"))
            .Take(80);
        var text = string.Join("\n", lines);
        if (text.Length == 0) text = log[..Math.Min(log.Length, 8000)];
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    }
}

public sealed partial class VantaServices
{
    private async Task<ProjectRecord> AgenticCompilerRepair(
        JobContext c,
        ModelRecord model,
        ProjectRecord original,
        int attempt,
        string diagnostics)
    {
        string fingerprint = ProjectFiles.Fingerprint(original);
        string checkpoint = "agentic-repair-" + attempt;
        var complete = c.Step(checkpoint + "-complete");
        if (complete != null && complete.Str("input_fingerprint") == fingerprint)
            return complete["project"]!.Deserialize<ProjectRecord>(JsonEx.Options)!;

        var targets = ForgeAgenticRepair.Targets(original, diagnostics);
        if (targets.Count == 0)
            throw new ForgeFailure(
                "Compiler-led repair",
                "The compiler log does not identify a safe production source file to edit.",
                "The saved source and complete diagnostics remain available. No broad rewrite was attempted.");

        var changed = new List<ProjectFile>();
        int done = 0;
        foreach (var path in targets)
        {
            c.Token.ThrowIfCancellationRequested();
            string record = checkpoint + "-file-" + AndroidForgePreparation.Hash(path)[..20];
            var saved = c.Step(record);
            ProjectFile file;
            if (saved != null && saved.Str("input_fingerprint") == fingerprint)
            {
                file = saved["file"]!.Deserialize<ProjectFile>(JsonEx.Options)!;
            }
            else
            {
                c.Stage("Agentic repair",
                    "Compiler selected " + (done + 1) + " of " + targets.Count + ": " + path,
                    done, targets.Count);

                var working = JsonEx.Clone(original);
                working.Files = ProjectFiles.MergeUnchecked(working.Files, changed);
                string context = ForgeContext.Pack(
                    model,
                    working,
                    path,
                    "Repair exactly this compiler-implicated production file. Keep the smallest coherent change.",
                    diagnostics);

                string instructions =
                    "You are Vanta's compiler-led coding agent. Return only JSON "
                    + "{path:string,content:string,encoding:'utf-8'} for the ONE requested production file. "
                    + "Do not return a plan. Do not edit, delete, skip, rename away or weaken tests. "
                    + "Do not disable lint or quality gates. Do not remove requested behaviour merely to compile. "
                    + "Do not replace working functionality with TODOs, stubs or mocks. "
                    + "Preserve application identity and security. The real compiler runs immediately after this repair.";

                var reply = await ForgeCall(c, model, context, instructions, 16384, record);
                var value = ProjectFiles.ParseObject(reply.Text);
                file = new ProjectFile
                {
                    Path = ProjectFiles.Normalize(value.Str("path")),
                    Content = value.Str("content"),
                    Encoding = value.Str("encoding", "utf-8")
                };
                if (!file.Path.Equals(path, StringComparison.OrdinalIgnoreCase))
                    throw new ForgeFailure("Agentic repair", "The model returned a different file than the compiler-selected target: " + path + ".");
                if (string.IsNullOrWhiteSpace(file.Content))
                    throw new ForgeFailure("Agentic repair", "The model returned an empty source file: " + path);
                if (ForgeAgenticRepair.TestPath(file.Path))
                    throw new ForgeFailure("Repair would weaken verification", "The coding agent attempted to edit a test file: " + file.Path + ".");
                ProjectFiles.Validate([file]);
                c.Step(record, new JsonObject
                {
                    ["input_fingerprint"] = fingerprint,
                    ["file"] = JsonSerializer.SerializeToNode(file),
                    ["model"] = model.Key
                });
            }
            changed.Add(file);
            done++;
        }

        var result = JsonEx.Clone(original);
        var beforeTests = original.Files
            .Where(f => ForgeAgenticRepair.TestPath(f.Path))
            .ToDictionary(f => f.Path, f => f.Content, StringComparer.OrdinalIgnoreCase);

        result.Files = ProjectFiles.Merge(result.Files, changed);
        AndroidForgePreparation.PreserveManaged(original, result);

        foreach (var test in result.Files.Where(f => ForgeAgenticRepair.TestPath(f.Path)))
            if (!beforeTests.TryGetValue(test.Path, out var before) || before != test.Content)
                throw new ForgeFailure("Repair would weaken verification", "A compiler repair changed a protected test: " + test.Path + ".");

        if (ProjectFiles.Fingerprint(result) == fingerprint)
            throw new ForgeFailure("Repair made no change", "The compiler-led repair produced no source change.", "The same build will not be submitted again.");

        result.ModelKey = model.Key;
        c.Step(checkpoint + "-complete", new JsonObject
        {
            ["input_fingerprint"] = fingerprint,
            ["project"] = JsonSerializer.SerializeToNode(result)
        });
        return result;
    }

    private bool AgenticNoProgress(JobContext c, ProjectRecord project, string log)
    {
        string signature = ForgeAgenticRepair.DiagnosticSignature(log);
        var state = c.Step("agentic-stagnation") ?? new JsonObject();
        int repeat = state.Str("signature") == signature ? (int)state.Num("repeat", 0) + 1 : 1;
        state["signature"] = signature;
        state["repeat"] = repeat;
        state["source_fingerprint"] = ProjectFiles.Fingerprint(project);
        c.Step("agentic-stagnation", state);
        return repeat >= 3;
    }
}
