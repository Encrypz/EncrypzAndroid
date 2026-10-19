using System.Security.Cryptography;
using MongoDB.Bson.Serialization.Attributes;
using MongoDB.Bson;
using MongoDB.Driver;

namespace Encrypz.API.Services;

public sealed class MobileAccount
{
    [BsonId] public string Subject { get; set; } = "";
    public string Email { get; set; } = "";
    public string PasswordSaltHex { get; set; } = "";
    public string WrappedVaultKeyHex { get; set; } = "";
    public byte[] ProofSalt { get; set; } = [];
    public byte[] ProofHash { get; set; } = [];
    public int CredentialVersion { get; set; } = 2;
}

public static class AccountProof
{
    public static byte[] Hash(string proof, byte[] salt) =>
        Rfc2898DeriveBytes.Pbkdf2(Convert.FromHexString(proof), salt, 600_000, HashAlgorithmName.SHA256, 32);
    public static bool Verify(string proof, MobileAccount account) =>
        CryptographicOperations.FixedTimeEquals(Hash(proof, account.ProofSalt), account.ProofHash);
}

public interface IMobileAccountStore
{
    Task<MobileAccount?> Find(string subject, CancellationToken ct);
    Task<bool> Create(MobileAccount account, CancellationToken ct);
    Task<MobileAccount?> FindLegacy(string email, CancellationToken ct);
    Task Ping(CancellationToken ct);
}

public sealed class MobileAccountStore : IMobileAccountStore
{
    private readonly IMongoCollection<MobileAccount>? accounts;
    public MobileAccountStore(IConfiguration configuration)
    {
        var uri = configuration["MongoDB:ConnectionString"];
        if (string.IsNullOrWhiteSpace(uri)) return;
        var settings = MongoClientSettings.FromConnectionString(uri);
        settings.ServerSelectionTimeout = TimeSpan.FromSeconds(10);
        settings.ConnectTimeout = TimeSpan.FromSeconds(10);
        accounts = new MongoClient(settings).GetDatabase("encrypz")
            .GetCollection<MobileAccount>("mobile_accounts");
    }
    private IMongoCollection<MobileAccount> Accounts => accounts ?? throw new InvalidOperationException("Account service is not configured.");
    public Task<MobileAccount?> Find(string subject, CancellationToken ct) =>
        Accounts.Find(x => x.Subject == subject).FirstOrDefaultAsync(ct)!;
    public async Task Ping(CancellationToken ct) =>
        await Accounts.Database.RunCommandAsync<BsonDocument>(new BsonDocument("ping", 1), cancellationToken: ct);

    public async Task<MobileAccount?> FindLegacy(string email, CancellationToken ct)
    {
        // Only called using the email verified by Google. Never accept a client-supplied owner.
        var document = await Accounts.Database.GetCollection<BsonDocument>("vault_users")
            .Find(new BsonDocument("email", email)).FirstOrDefaultAsync(ct);
        if (document == null) return null;
        string Field(string name) => document.TryGetValue(name, out var value) && value.IsString ? value.AsString : "";
        var salt = Field("passwordSaltHex"); var proof = Field("passwordVerifierHex"); var wrapped = Field("wrappedVaultKeyHex");
        if (salt.Length != 64 || proof.Length != 64 || wrapped.Length != 120 ||
            !(salt + proof + wrapped).All(Uri.IsHexDigit)) throw new InvalidOperationException("Legacy account requires recovery.");
        var account = new MobileAccount { Email = email, PasswordSaltHex = salt, WrappedVaultKeyHex = wrapped,
            CredentialVersion = 1, ProofSalt = RandomNumberGenerator.GetBytes(32) };
        account.ProofHash = AccountProof.Hash(proof, account.ProofSalt);
        return account;
    }

    public async Task<bool> Create(MobileAccount account, CancellationToken ct)
    {
        try { await Accounts.InsertOneAsync(account, cancellationToken: ct); return true; }
        catch (MongoWriteException e) when (e.WriteError.Category == ServerErrorCategory.DuplicateKey) { return false; }
    }
}
