using System.Security.Cryptography;
using Encrypz.API.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using MongoDB.Driver;

namespace Encrypz.API.Controllers;

// These endpoints use Google bearer tokens exclusively, never browser session cookies.
[ApiController, Route("api/mobile/accounts"), EnableRateLimiting("credentials")]
[RequestSizeLimit(8192), ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class MobileAccountsController(MobileGoogleIdentity google, IMobileAccountStore store) : ControllerBase
{
    public sealed record Credentials(string Proof, string? PasswordSaltHex = null, string? WrappedVaultKeyHex = null);
    private static bool Hex(string? value, int length) => value?.Length == length && value.All(Uri.IsHexDigit);

    [HttpGet]
    public Task<IActionResult> Lookup(CancellationToken ct) => Run(async identity => {
        var account = await store.Find(identity.Subject, ct) ?? await store.FindLegacy(identity.Email, ct);
        return Ok(new { exists = account != null, passwordSaltHex = account?.PasswordSaltHex, credentialVersion = account?.CredentialVersion ?? 2 });
    }, ct);

    [HttpPost("register")]
    public Task<IActionResult> Register(Credentials input, CancellationToken ct) => Run(async identity => {
        if (!Hex(input.Proof, 64) || !Hex(input.PasswordSaltHex, 64) || !Hex(input.WrappedVaultKeyHex, 120))
            return BadRequest(new { message = "Invalid account credentials." });
        if (await store.Find(identity.Subject, ct) != null || await store.FindLegacy(identity.Email, ct) != null)
            return Conflict(new { message = "Account already exists. Sign in to restore its original key." });
        var account = new MobileAccount {
            Subject = identity.Subject, Email = identity.Email,
            PasswordSaltHex = input.PasswordSaltHex!, WrappedVaultKeyHex = input.WrappedVaultKeyHex!,
            ProofSalt = RandomNumberGenerator.GetBytes(32)
        };
        account.ProofHash = AccountProof.Hash(input.Proof, account.ProofSalt);
        if (!await store.Create(account, ct)) return Conflict(new { message = "Account already exists. Sign in instead." });
        return Ok(new { account.Email, account.PasswordSaltHex, account.WrappedVaultKeyHex, account.CredentialVersion });
    }, ct);

    [HttpPost("login")]
    public Task<IActionResult> Login(Credentials input, CancellationToken ct) => Run(async identity => {
        if (!Hex(input.Proof, 64)) return BadRequest();
        var account = await store.Find(identity.Subject, ct) ?? await store.FindLegacy(identity.Email, ct);
        if (account == null) return NotFound();
        if (!AccountProof.Verify(input.Proof, account)) return Unauthorized();
        if (string.IsNullOrEmpty(account.Subject)) {
            // Adopt legacy key only after both Google ownership and password proof are verified.
            account.Subject = identity.Subject;
            if (!await store.Create(account, ct)) {
                account = await store.Find(identity.Subject, ct);
                if (account == null || !AccountProof.Verify(input.Proof, account)) return Unauthorized();
            }
        }
        return Ok(new { account.Email, account.PasswordSaltHex, account.WrappedVaultKeyHex, account.CredentialVersion });
    }, ct);

    private async Task<IActionResult> Run(Func<(string Subject, string Email), Task<IActionResult>> action, CancellationToken ct)
    {
        try {
            var identity = await google.Verify(Request.Headers.Authorization.ToString(), ct);
            if (identity == null) return Unauthorized();
            return await action(identity.Value);
        }
        catch (Exception e) when (e is MongoException or TimeoutException or HttpRequestException or InvalidOperationException) {
            // Never send database connection details or driver errors to clients.
            return StatusCode(503, new { message = "Account service unavailable. Please retry later." });
        }
    }
}
