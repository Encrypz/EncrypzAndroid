using System.Security.Cryptography;
using MongoDB.Bson.Serialization.Attributes;
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
    public async Task<bool> Create(MobileAccount account, CancellationToken ct)
    {
        try { await Accounts.InsertOneAsync(account, cancellationToken: ct); return true; }
        catch (MongoWriteException e) when (e.WriteError.Category == ServerErrorCategory.DuplicateKey) { return false; }
    }
}
