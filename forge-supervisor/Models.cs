using System.Text.Json;

record SettingsInput
{
    public string ProviderName { get; init; } = "OpenRouter";
    public string ProviderBaseUrl { get; init; } = "https://openrouter.ai/api/v1";
    public string ProviderApiKey { get; init; } = "";
    public string Model { get; init; } = "qwen/qwen3-coder-next";
    public string GithubRepo { get; init; } = "RoninMerc/RonisOS-BPlus";
    public string GithubBranch { get; init; } = "vanta-forge-worker";
    public string GithubToken { get; init; } = "";
    public string Platform { get; init; } = "android";
    public int MaxCycles { get; init; } = 50;
}
record StoredSettings
{
    public string ProviderName { get; set; } = "OpenRouter";
    public string ProviderBaseUrl { get; set; } = "https://openrouter.ai/api/v1";
    public string ProviderApiKeyProtected { get; set; } = "";
    public string Model { get; set; } = "qwen/qwen3-coder-next";
    public string GithubRepo { get; set; } = "RoninMerc/RonisOS-BPlus";
    public string GithubBranch { get; set; } = "vanta-forge-worker";
    public string GithubTokenProtected { get; set; } = "";
    public string Platform { get; set; } = "android";
    public int MaxCycles { get; set; } = 50;
}
record ProjectInfo(string Id, string Name, string Root, List<string> Files, DateTimeOffset Imported);
record LedgerEntry(int Cycle, DateTimeOffset Time, string SourceHash, string ErrorHash, string Classification, string Summary, List<string> ChangedFiles, string Outcome);
record BuildResult(bool Success, string Log, string Artifact);
record RepairFile { public string Path { get; set; } = ""; public string Content { get; set; } = ""; }
record RepairResponse { public string Summary { get; set; } = ""; public List<RepairFile> Files { get; set; } = []; }

record SupervisorState
{
    public string Status { get; set; } = "IDLE";
    public string ProjectId { get; set; } = "";
    public string ProjectName { get; set; } = "";
    public int Cycle { get; set; }
    public string Stage { get; set; } = "";
    public string Message { get; set; } = "";
    public string LastError { get; set; } = "";
    public string Artifact { get; set; } = "";
    public DateTimeOffset Updated { get; set; } = DateTimeOffset.UtcNow;
    public List<string> Recent { get; set; } = [];
}

static class JsonOpts
{
    public static JsonSerializerOptions Options { get; } = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };
}