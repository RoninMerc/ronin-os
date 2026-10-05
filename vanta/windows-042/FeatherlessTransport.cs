using System.Collections.Concurrent;
using System.Net;
using System.Text.Json.Nodes;

namespace Vanta.Core;

public sealed partial class ProviderApi
{
    private readonly ConcurrentDictionary<string, JsonObject> catalogueDiagnostics = new();
    public JsonObject CatalogueDiagnostic(string provider) => catalogueDiagnostics.TryGetValue(provider, out var value) ? value.Copy() : new();
    public async Task<JsonObject> CatalogueJsonAsync(Provider provider, string url, CancellationToken ct)
    {
        if (provider.Id != "featherless") return await JsonAsync(provider, HttpMethod.Get, url, null, ct);
        var uri = Https(url);
        if (uri.AbsolutePath != new Uri(provider.BaseUrl).AbsolutePath.TrimEnd('/') + "/models") throw new VantaException("Catalogue discovery accepts only the model-list endpoint.");
        using var limit = Limit(ct, 120);
        // Public discovery is the documented normal route, NOT a fallback after an account error.
        // Keep provider-origin validation but do not attach the inference credential.
        using var request = Request(provider, HttpMethod.Get, url, includeCredentials: false);
        request.Headers.Accept.ParseAdd("application/json");
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, limit.Token);
        string Header(string name)
        {
            if (!response.Headers.TryGetValues(name, out var values)) return "";
            string value = UserErrors.Clean(string.Join(", ", values)).Replace('\r', ' ').Replace('\n', ' ');
            return value[..Math.Min(200, value.Length)];
        }
        var diagnostic = new JsonObject { ["time_utc"] = DateTimeOffset.UtcNow.ToString("O"), ["operation"] = "public model catalogue GET", ["endpoint"] = uri.AbsoluteUri, ["http_status"] = (int)response.StatusCode, ["authenticated"] = false, ["retry_after"] = Header("Retry-After"), ["request_id"] = Header("x-request-id"), ["edge_request_id"] = Header("cf-ray"), ["content_type"] = response.Content.Headers.ContentType?.MediaType ?? "" };
        catalogueDiagnostics[provider.Id] = diagnostic.Copy();
        try { await CheckAsync(response, limit.Token, true); }
        catch (ProviderHttpException error) { diagnostic["provider_code"] = error.ErrorCode; diagnostic["error"] = UserErrors.Clean(error.Message); catalogueDiagnostics[provider.Id] = diagnostic.Copy(); throw; }
        var bytes = await ReadBytesAsync(response.Content, 32 * 1024 * 1024, null, limit.Token);
        diagnostic["received_bytes"] = bytes.Length; catalogueDiagnostics[provider.Id] = diagnostic.Copy();
        return JsonNode.Parse(bytes) as JsonObject ?? throw new VantaException("The model catalogue response is not a JSON object.");
    }
    public async Task CheckFeatherlessKeyAsync(CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(keys("featherless"))) throw new VantaException("Save your Featherless API key first.");
        // One authenticated GET; never purchases a completion or refreshes the catalogue.
        await JsonAsync(Provider.Get("featherless"), HttpMethod.Get, Provider.Get("featherless").BaseUrl + "/plan", null, ct);
    }
}
