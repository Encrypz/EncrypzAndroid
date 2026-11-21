# Contributing to Encrypz Android

Thank you for considering contributing to **Encrypz** — a zero-knowledge, serverless encrypted vault for Android written in Kotlin and Jetpack Compose.

## Local Setup

1. **Fork the repository** on GitHub and clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/Encrypz.git
   cd Encrypz
   ```
2. **Install JDK 17** and Android Studio / Android SDK (compile/target SDK 36, min SDK 24).
3. **Create local config files (gitignored):**
   ```bash
   cp Encrypz.Android/local.properties.example Encrypz.Android/local.properties
   ```
   Set `sdk.dir` to your local Android SDK path (and optional Google OAuth keys if testing Google Drive sync).
4. **Run unit tests and compile:**
   ```bash
   ./Encrypz.Android/gradlew -p Encrypz.Android :app:testDebugUnitTest :app:compileDebugKotlin
   ```

## Code Organization

* `Encrypz.Android/app/src/main/java/com/encrypz/privacy/crypto/` — Pure Kotlin/Java cryptographic primitives (`scrypt`, RFC 3394 AES Key Wrap, RFC 5297 AES-256-SIV, `EPZ2` chunked AES-256-GCM). Keep this layer pure and thoroughly covered by test vectors.
* `Encrypz.Android/app/src/main/java/com/encrypz/privacy/vault/` — Vault engine and storage backends (`LocalVaultBackend`, `SafVaultBackend`, `GoogleDriveVaultBackend`).
* `Encrypz.Android/app/src/main/java/com/encrypz/privacy/drive/` — Direct Google Drive REST v3 API integration and background upload worker.
* `Encrypz.Android/app/src/main/java/com/encrypz/privacy/auth/` — Account picker, PKCE loopback OAuth helper, biometrics, and PIN handling.
* `Encrypz.Android/app/src/main/java/com/encrypz/privacy/ui/` — Jetpack Compose screens and `VaultViewModel`.

## Security & Privacy Rules

* **Never commit secrets**: Never commit `local.properties`, `signing.properties`, `.env`, keystores (`*.jks`), certificates (`*.pem`), or OAuth client secrets.
* **Keep vault operations client-side**: Do not introduce external backend servers, telemetry, or analytics SDKs.

## Submitting a Pull Request

1. Create a feature branch (`git checkout -b feature/your-change`).
2. Add or update JVM unit tests in `Encrypz.Android/app/src/test/`.
3. Verify `./Encrypz.Android/gradlew -p Encrypz.Android :app:testDebugUnitTest` passes with zero failures or skipped tests.
4. Open a pull request against `main` with a clear summary of your changes and test results.
