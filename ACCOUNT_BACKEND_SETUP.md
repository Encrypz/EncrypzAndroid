# Android account backend

The Android APK no longer connects directly to Atlas. The .NET 8 API stores verified Google email, a salted PBKDF2-SHA256 hash of the client login proof (600,000 iterations), and a password-encrypted vault key in `encrypz.mobile_accounts`. The Android client derives the key with PBKDF2-SHA256 (600,000 iterations). Neither the master password nor the unwrapped vault key is sent to the API. PINs stay on the device.

## Deployment

1. Deploy `Encrypz.API` to a .NET 8 host with a public HTTPS address. Existing browser Google OAuth configuration is still required by the application. See `.env.example` for configuration names.
2. Configure the secret `MongoDB__ConnectionString` on that host. The supplied Atlas connection has been saved only in the ignored root `.env` for local use; deployment must use the host's secret settings. Grant the database user access only to the `encrypz` database. Allow the backend's outbound IP in Atlas Network Access.
3. Configure Google OAuth with the app package `com.encrypz.vault`, the installed APK signing fingerprint, and the Drive file and userinfo.email scopes. Keep testing users approved while OAuth is in testing.
4. Add `ACCOUNT_API_BASE_URL=https://YOUR-API-HOST` (origin only, without `/api`) to `Encrypz.Android/local.properties`, then rebuild the APK. No database credentials belong in this file or APK anymore.
5. Create an account on device A, upload a file, then sign into the same Google account with the same master password on device B and sync Drive. Verify the file decrypts. Test wrong passwords and duplicate registration.

Until step 4 is completed, the app reports that the account server is not deployed and does not claim an account was saved. The API has not been deployed by this change.

## Existing installations

An existing local account is enrolled on its next successful master-password login once the backend is configured. Use the original device first: its saved encrypted vault key is needed. Legacy `vault_users` documents are not automatically trusted or overwritten. Losing the original device before enrollment may prevent restoration of legacy files. No password-reset mechanism is implemented; changing a password without rewrapping the key would lose access to files.

The earlier Android implementation bundled Atlas credentials. Rotate that database password in Atlas and update the backend secret before distributing this APK. Remove old `MONGO_*` values from Android local configuration. Never commit `.env`.

## Verification

`dotnet test Encrypz.Tests/Encrypz.Tests.csproj`

From `Encrypz.Android`: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease`

Tests use generated credentials and mocked Google responses, not production Atlas accounts. The account API rate-limits credential attempts and rejects cookie-only requests; Google bearer tokens must verify the email.
