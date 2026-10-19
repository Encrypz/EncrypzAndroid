using System.Security.Cryptography;
using System.Net;
using System.Net.Http.Headers;
using Encrypz.API.Services;
using Xunit;

namespace Encrypz.Tests;

public class MobileAccountTests
{
    [Fact] public void ProofIsSaltedAndRejectsWrongPassword()
    {
        var proof = new string('a', 64);
        var account = new MobileAccount { ProofSalt = RandomNumberGenerator.GetBytes(32) };
        account.ProofHash = AccountProof.Hash(proof, account.ProofSalt);
        Assert.True(AccountProof.Verify(proof, account));
        Assert.False(AccountProof.Verify(new string('b', 64), account));
        Assert.NotEqual(account.ProofHash, AccountProof.Hash(proof, RandomNumberGenerator.GetBytes(32)));
        Assert.NotEqual(Convert.FromHexString(proof), account.ProofHash);
    }
    [Theory]
    [InlineData("{\"sub\":\"123\",\"email\":\"test@example.com\",\"email_verified\":false}")]
    [InlineData("{\"sub\":\"123\",\"email\":\"test@example.com\"}")]
    public async Task UnverifiedGoogleEmailIsRejected(string json)
    {
        var identity = new MobileGoogleIdentity(new HttpClient(new GoogleHandler(json)));
        Assert.Null(await identity.Verify("Bearer token", default));
    }
    [Fact] public async Task GoogleIdentityComesFromGoogleNotRequestEmail()
    {
        var identity = new MobileGoogleIdentity(new HttpClient(new GoogleHandler(
            "{\"sub\":\"123\",\"email\":\"Test@Example.com\",\"email_verified\":true}")));
        Assert.Equal(("123", "test@example.com"), await identity.Verify("Bearer token", default));
        Assert.Null(await identity.Verify("", default));
    }
    [Fact] public async Task DuplicateRegistrationCannotReplaceOriginalKeyAndLoginRequiresProof()
    {
        var store = new FakeStore();
        var google = new MobileGoogleIdentity(new HttpClient(new GoogleHandler(
            "{\"sub\":\"123\",\"email\":\"test@example.com\",\"email_verified\":true}")));
        var controller = new Encrypz.API.Controllers.MobileAccountsController(google, store);
        controller.ControllerContext = new Microsoft.AspNetCore.Mvc.ControllerContext {
            HttpContext = new Microsoft.AspNetCore.Http.DefaultHttpContext()
        };
        var input = new Encrypz.API.Controllers.MobileAccountsController.Credentials(
            new string('a', 64), new string('b', 64), new string('c', 120));
        Assert.IsType<Microsoft.AspNetCore.Mvc.UnauthorizedResult>(await controller.Register(input, default));
        Assert.Null(store.Account);
        controller.Request.Headers.Authorization = "Bearer token";
        Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.Register(input, default));
        Assert.IsType<Microsoft.AspNetCore.Mvc.ConflictObjectResult>(await controller.Register(
            input with { WrappedVaultKeyHex = new string('d', 120) }, default));
        Assert.Equal(new string('c', 120), store.Account!.WrappedVaultKeyHex);
        Assert.IsType<Microsoft.AspNetCore.Mvc.UnauthorizedResult>(await controller.Login(input with { Proof = new string('f', 64) }, default));
        var login = Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.Login(input, default));
        var loginJson = System.Text.Json.JsonSerializer.Serialize(login.Value);
        Assert.Contains(new string('c', 120), loginJson);
        Assert.DoesNotContain("ProofHash", loginJson);
        var lookup = Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.Lookup(default));
        var lookupJson = System.Text.Json.JsonSerializer.Serialize(lookup.Value);
        Assert.DoesNotContain("WrappedVaultKey", lookupJson);
        Assert.DoesNotContain("Proof", lookupJson);
    }
    [Fact] public async Task LegacyKeyIsRestoredOnlyAfterGoogleOwnershipAndCorrectPassword()
    {
        var proof = new string('a', 64);
        var legacy = new MobileAccount { Email = "test@example.com", CredentialVersion = 1,
            PasswordSaltHex = new string('b', 64), WrappedVaultKeyHex = new string('c', 120),
            ProofSalt = RandomNumberGenerator.GetBytes(32) };
        legacy.ProofHash = AccountProof.Hash(proof, legacy.ProofSalt);
        var store = new FakeStore { Legacy = legacy };
        var google = new MobileGoogleIdentity(new HttpClient(new GoogleHandler(
            "{\"sub\":\"123\",\"email\":\"test@example.com\",\"email_verified\":true}")));
        var controller = new Encrypz.API.Controllers.MobileAccountsController(google, store);
        controller.ControllerContext = new Microsoft.AspNetCore.Mvc.ControllerContext {
            HttpContext = new Microsoft.AspNetCore.Http.DefaultHttpContext()
        };
        controller.Request.Headers.Authorization = "Bearer token";
        var input = new Encrypz.API.Controllers.MobileAccountsController.Credentials(proof, legacy.PasswordSaltHex, legacy.WrappedVaultKeyHex);
        Assert.IsType<Microsoft.AspNetCore.Mvc.ConflictObjectResult>(await controller.Register(input, default));
        Assert.Null(store.Account);
        Assert.IsType<Microsoft.AspNetCore.Mvc.UnauthorizedResult>(await controller.Login(input with { Proof = new string('d', 64) }, default));
        Assert.Null(store.Account);
        Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.Login(input, default));
        Assert.Equal("123", store.Account!.Subject);
        Assert.Equal(legacy.WrappedVaultKeyHex, store.Account.WrappedVaultKeyHex);
        Assert.Equal(1, store.Account.CredentialVersion);
        Assert.Null(await store.FindLegacy("different@example.com", default));
    }
    private sealed class FakeStore : IMobileAccountStore
    {
        public MobileAccount? Account;
        public MobileAccount? Legacy;
        public Task Ping(CancellationToken ct) => Task.CompletedTask;
        public Task<MobileAccount?> FindLegacy(string email, CancellationToken ct) => Task.FromResult(Legacy?.Email == email ? Legacy : null);
        public Task<MobileAccount?> Find(string subject, CancellationToken ct) => Task.FromResult(Account?.Subject == subject ? Account : null);
        public Task<bool> Create(MobileAccount account, CancellationToken ct) {
            if (Account != null) return Task.FromResult(false);
            Account = account; return Task.FromResult(true);
        }
    }
    private sealed class GoogleHandler(string json) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Assert.Equal("openidconnect.googleapis.com", request.RequestUri!.Host);
            Assert.Equal(new AuthenticationHeaderValue("Bearer", "token"), request.Headers.Authorization);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json) });
        }
    }
}
