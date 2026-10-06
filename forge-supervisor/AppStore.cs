using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

sealed class AppStore
{
    public string Root { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RoninForgeSupervisor");
    string SettingsFile => Path.Combine(Root, "settings.json");
    string ProjectsFile => Path.Combine(Root, "projects.json");

    public AppStore()
    {
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(Path.Combine(Root, "projects"));
        Directory.CreateDirectory(Path.Combine(Root, "artifacts"));
    }

    static string Protect(string s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(s), null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(bytes);
    }

    static string Unprotect(string s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(s), null, DataProtectionScope.CurrentUser));
        }
        catch { return ""; }
    }

    public StoredSettings LoadSettings()
    {
        if (!File.Exists(SettingsFile)) return new();
        try { return JsonSerializer.Deserialize<StoredSettings>(File.ReadAllText(SettingsFile), JsonOpts.Options) ?? new(); }
        catch { return new(); }
    }

    public SettingsInput EffectiveSettings()
    {
        var s = LoadSettings();
        return new()
        {
            ProviderName = s.ProviderName,
            ProviderBaseUrl = s.ProviderBaseUrl,
            ProviderApiKey = Unprotect(s.ProviderApiKeyProtected),
            Model = s.Model,
            GithubRepo = s.GithubRepo,
            GithubBranch = s.GithubBranch,
            GithubToken = Unprotect(s.GithubTokenProtected),
            Platform = s.Platform,
            MaxCycles = s.MaxCycles
        };
    }

    public object PublicSettings()
    {
        var s = LoadSettings();
        return new
        {
            s.ProviderName,
            s.ProviderBaseUrl,
            providerKeySaved = !string.IsNullOrEmpty(s.ProviderApiKeyProtected),
            s.Model,
            s.GithubRepo,
            s.GithubBranch,
            githubTokenSaved = !string.IsNullOrEmpty(s.GithubTokenProtected),
            s.Platform,
            s.MaxCycles
        };
    }

    public void SaveSettings(SettingsInput i)
    {
        var old = LoadSettings();
        var s = new StoredSettings
        {
            ProviderName = i.ProviderName,
            ProviderBaseUrl = i.ProviderBaseUrl,
            ProviderApiKeyProtected = string.IsNullOrWhiteSpace(i.ProviderApiKey) ? old.ProviderApiKeyProtected : Protect(i.ProviderApiKey),
            Model = i.Model,
            GithubRepo = i.GithubRepo,
            GithubBranch = i.GithubBranch,
            GithubTokenProtected = string.IsNullOrWhiteSpace(i.GithubToken) ? old.GithubTokenProtected : Protect(i.GithubToken),
            Platform = i.Platform,
            MaxCycles = Math.Clamp(i.MaxCycles, 1, 500)
        };
        File.WriteAllText(SettingsFile, JsonSerializer.Serialize(s, JsonOpts.Options));
    }

    public List<ProjectInfo> ListProjects()
    {
        if (!File.Exists(ProjectsFile)) return [];
        try { return JsonSerializer.Deserialize<List<ProjectInfo>>(File.ReadAllText(ProjectsFile), JsonOpts.Options) ?? []; }
        catch { return []; }
    }

    public ProjectInfo GetProject(string id) =>
        ListProjects().FirstOrDefault(x => x.Id == id) ?? throw new InvalidOperationException("Project not found");

    public async Task<ProjectInfo> ImportZip(Stream source, string name)
    {
        var id = Guid.NewGuid().ToString("N");
        var root = Path.Combine(Root, "projects", id, "source");
        Directory.CreateDirectory(root);
        var temp = Path.Combine(Root, "projects", id, "upload.zip");
        await using (var f = File.Create(temp)) await source.CopyToAsync(f);

        using var zip = ZipFile.OpenRead(temp);
        foreach (var e in zip.Entries)
        {
            if (string.IsNullOrEmpty(e.Name)) continue;
            var dest = Path.GetFullPath(Path.Combine(root, e.FullName.Replace('/', Path.DirectorySeparatorChar)));
            var prefix = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
            if (!dest.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Unsafe ZIP path");
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            e.ExtractToFile(dest, true);
        }

        var files = Files(root);
        var top = files.Select(x => x.Split('/')[0]).Distinct().ToList();
        if (top.Count == 1 && Directory.Exists(Path.Combine(root, top[0])))
        {
            var inner = Path.Combine(root, top[0]);
            var tmp = root + "-flat";
            Directory.Move(inner, tmp);
            Directory.Delete(root, true);
            Directory.Move(tmp, root);
            files = Files(root);
        }

        var p = new ProjectInfo(id, name, root, files, DateTimeOffset.UtcNow);
        var all = ListProjects();
        all.Add(p);
        File.WriteAllText(ProjectsFile, JsonSerializer.Serialize(all, JsonOpts.Options));
        return p;
    }

    static List<string> Files(string root) =>
        Directory.GetFiles(root, "*", SearchOption.AllDirectories)
            .Select(x => Path.GetRelativePath(root, x).Replace('\\', '/'))
            .Order()
            .ToList();

    public List<LedgerEntry> LoadLedger(string id)
    {
        var f = Path.Combine(Root, "projects", id, "ledger.json");
        if (!File.Exists(f)) return [];
        try { return JsonSerializer.Deserialize<List<LedgerEntry>>(File.ReadAllText(f), JsonOpts.Options) ?? []; }
        catch { return []; }
    }

    public void AppendLedger(string id, LedgerEntry entry)
    {
        var l = LoadLedger(id);
        l.Add(entry);
        File.WriteAllText(Path.Combine(Root, "projects", id, "ledger.json"), JsonSerializer.Serialize(l, JsonOpts.Options));
    }
}