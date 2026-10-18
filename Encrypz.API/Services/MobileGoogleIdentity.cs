using System.Net.Http.Headers;
using System.Text.Json;

namespace Encrypz.API.Services;

public sealed class MobileGoogleIdentity(HttpClient client)
{
    public async Task<(string Subject, string Email)?> Verify(string authorization, CancellationToken ct)
    {
        if (!AuthenticationHeaderValue.TryParse(authorization, out var header) ||
            !header.Scheme.Equals("Bearer", StringComparison.OrdinalIgnoreCase) ||
            string.IsNullOrWhiteSpace(header.Parameter) || header.Parameter.Length > 4096) return null;
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://openidconnect.googleapis.com/v1/userinfo");
        request.Headers.Authorization = header;
        using var response = await client.SendAsync(request, ct);
        if (!response.IsSuccessStatusCode) return null;
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
        var root = json.RootElement;
        if (!root.TryGetProperty("email_verified", out var verified) || verified.ValueKind != JsonValueKind.True ||
            !root.TryGetProperty("sub", out var sub) || !root.TryGetProperty("email", out var email)) return null;
        var subject = sub.GetString(); var address = email.GetString();
        return string.IsNullOrWhiteSpace(subject) || string.IsNullOrWhiteSpace(address) ? null
            : (subject, address.Trim().ToLowerInvariant());
    }
}
