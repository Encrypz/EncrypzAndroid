# Encrypz Android

Encrypz is a zero-knowledge, serverless encrypted file vault for Android (`com.encrypz.privacy`) built with **Kotlin** and **Jetpack Compose**. Every key derivation, filename obfuscation, and file encryption runs **100% locally on your device** — no Encrypz backend server or external database is used.

Create completely offline vaults in **Phone Storage** or a chosen **Folder on Phone**, or optionally sync directly to your own **Google Drive** using the **Encrypz Vault V2 (`EPZ2`)** format.

## Contents

- [Features](#features)
- [Getting started as a user](#getting-started-as-a-user)
- [Architecture and repository layout](#architecture-and-repository-layout)
- [Requirements](#requirements)
- [Google configuration](#google-configuration)
- [Android setup and builds](#android-setup-and-builds)
- [Security and storage](#security-and-storage)
- [Restore and recovery](#restore-and-recovery)
- [Current limitations](#current-limitations)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Contributing and license](#contributing-and-license)

## Features

### Multi-vault & zero-server design

- **Phone Storage (Offline)**: Encrypted vaults stored privately on your phone with zero internet permissions needed.
- **Folder on Phone (Offline)**: Pick any folder on device storage (or SD card) via Android's Storage Access Framework (`OpenDocumentTree`); reopen existing vault folders at any time.
- **Google Drive Vaults (Optional Direct Sync)**: Connects directly from Android to Google Drive REST v3 using native Android authorization or PKCE loopback OAuth — no intermediary backend.
- **Encrypz Vault V2 (`EPZ2`) Format**:
  - `masterkey.encrypz`: `encrypz.key.v2` JSON containing 256-bit primary and 256-bit HMAC master keys wrapped with RFC 7914 `scrypt` (`N=32768`, `r=8`, `p=1`, 16-byte random salt), RFC 3394 AES Key Wrap, and KDF-bound `HMAC-SHA256` integrity MAC.
  - `vault.encrypz`: `encrypz.vault.v2` authenticated JSON manifest (`cipherSuite = ENCRYPZ_AES256_SIV_GCM_V2`) signed with `HMAC-SHA256`.
  - `vault/XX/YYYY...`: SHA-256 directory sharding with domain-separated RFC 5297 `AES-256-SIV` `.epz` / `.epzl` encrypted filenames.
  - `EPZ2` 68-byte binary header + **64 KiB** chunked `AES-256-GCM` content encryption with per-file content keys and header/chunk AAD authentication.
  - **Base32 Recovery Key**: Offline CRC-16/CCITT checksummed recovery key generated at vault creation to reset forgotten master passwords without any server.

### File management & media previews

- Import up to **30 files per batch** (up to **15 MiB** per file); all encryption happens on-device.
- Browse encrypted folders, search, and filter by category (All / Photos / Videos / Documents).
- Built-in three-column Photos timeline grouped by original creation date (newest first) with full-screen swipe/zoom viewer.
- Encrypted text notes, in-app document/media previews, Recycle Bin restore/shredding, and plaintext export via Android's destination picker.

### Access protection & notifications

- Master-password unlock (`12+` characters recommended at creation), optional **6-digit PIN** quick unlock (leading zeroes supported), and Android biometric/fingerprint unlock.
- Immediate vault auto-lock when backgrounded (`onStop`), clearing active keys from memory.
- Change Master Password (re-wraps `masterkey.encrypz` without re-encrypting files) and Recovery Key password reset.
- Screenshot and Recents preview protection (`FLAG_SECURE`) enabled by default, toggleable in Security.
- In-app notification inbox and Android system transfer notifications.

## Getting started as a user

1. Install the signed APK (`v2.0.0`, `com.encrypz.privacy`) or download from Google Play.
2. On first launch, tap **Create New Vault** (or **Select Existing Vault on Phone** / **Open Existing Google Drive Vault**).
3. Pick **Phone Storage**, **Folder on Phone**, or **Google Drive**, name your vault, and set a strong master password.
4. Save your **Base32 Recovery Key** offline — there is no server or password-reset email.
5. Optionally enable 6-digit PIN or biometric quick unlock.

Install updates using the same signing certificate. Uninstalling removes app-private `LOCAL` vaults, while **Folder on Phone** and **Google Drive** vaults remain intact and can be reopened on reinstall.

## Architecture and repository layout

```text
Encrypz Android (100% On-Device · No Server · No External Database)
  ├── Phone Storage / Folder on Phone ─► On-device storage (vault.encrypz, masterkey.encrypz, vault/XX/YYYY)
  └── Google Drive Vaults ─────────────► Google Drive REST v3 directly (vault.encrypz, masterkey.encrypz, vault/XX/YYYY)
```

| Path | Purpose |
| --- | --- |
| `Encrypz.Android/` | Kotlin / Jetpack Compose Android application (`v2.0.0`, `com.encrypz.privacy`) |
| `Encrypz.Android/app/src/main/java/com/encrypz/privacy/crypto/vault/` | `scrypt`, RFC 3394 AES Key Wrap, RFC 5297 AES-256-SIV, `EPZ2` 64 KiB AES-256-GCM streaming crypto |
| `Encrypz.Android/app/src/main/java/com/encrypz/privacy/vault/` | Vault engine, SAF phone folder backend, Google Drive backend, multi-vault registry |
| `Encrypz.Android/app/src/main/java/com/encrypz/privacy/drive/` | Direct Google Drive REST v3 sync and background WorkManager uploader |
| `Encrypz.Android/app/src/main/java/com/encrypz/privacy/auth/` | Google account chooser, PKCE loopback OAuth helper, biometrics, PIN lock |
| `Encrypz.Android/app/src/main/java/com/encrypz/privacy/ui/` | Jetpack Compose Material 3 UI (`VaultListScreen`, `LoginScreen`, `VaultScreen`, media viewers) |
| `Encrypz.Android/app/src/test/` | JVM unit tests (crypto vectors, Encrypz Vault V2 format, OAuth/ViewModel state, imports) |
| `Encrypz.Android/local.properties.example` | Placeholder template for local SDK path and Google Drive OAuth config |
| `Encrypz.Android/signing.properties.example` | Placeholder template for release keystore signing config |

## Requirements

| Component | Requirement |
| --- | --- |
| Android device | Android 7.0 (API 24) or newer; `targetSdk = 36` |
| Android SDK | Android Gradle Plugin 9.0.1 compatible SDK, Build-Tools & Platform 36 |
| Java | JDK 17 |
| Gradle | Included Gradle wrapper (`Encrypz.Android/gradlew`) |

Dependency versions are defined in [`Encrypz.Android/gradle/libs.versions.toml`](Encrypz.Android/gradle/libs.versions.toml) and [`Encrypz.Android/app/build.gradle.kts`](Encrypz.Android/app/build.gradle.kts).

## Google configuration

*(Only needed for **Google Drive** vaults. **Phone Storage** and **Folder on Phone** vaults work completely offline without any Google Cloud configuration.)*

1. Copy [`Encrypz.Android/local.properties.example`](Encrypz.Android/local.properties.example) to `Encrypz.Android/local.properties` (gitignored).
2. In your Google Cloud project, enable the **Google Drive API** and configure the OAuth consent screen.
3. Create an **Android** OAuth client for package `com.encrypz.privacy` with your release/upload APK **SHA-1** fingerprint (and a second Android client for your Google Play App Signing SHA-1 if distributing via `.aab`).
4. Optionally configure a **Web** OAuth client ID and secret in `Encrypz.Android/local.properties` with authorized redirect URI `http://localhost:5207/api/Auth/google-callback` for direct PKCE browser fallback.

## Android setup and builds

Open **`Encrypz.Android/`** in Android Studio or build from the command line:

```bash
cd Encrypz.Android
cp local.properties.example local.properties      # set sdk.dir and optional OAuth client keys
cp signing.properties.example signing.properties  # set release keystore paths/passwords
./gradlew :app:testDebugUnitTest :app:bundleRelease :app:assembleRelease
```

Release outputs:

- `Encrypz.Android/app/build/outputs/bundle/release/app-release.aab`
- `Encrypz.Android/app/build/outputs/apk/release/app-release.apk`

`local.properties`, `signing.properties`, keystores (`*.jks`), and compiled `.apk`/`.aab` files are ignored via `.gitignore`. See [`Encrypz.Android/RELEASE.md`](Encrypz.Android/RELEASE.md) for Play Console release notes.

## Security and storage

- All vault keys, `scrypt` key derivation, `AES-256-SIV` filenames, and **64 KiB** `EPZ2` `AES-256-GCM` chunk encryption run strictly on the user's Android device.
- No password, plaintext filename, or master key ever leaves the device or touches any Encrypz server or database.
- Android Keystore (`DeviceSecrets`) seals session unlock material for biometric/PIN quick unlock, and Android cloud auto-backup is disabled in `AndroidManifest.xml`.
- Exporting a file writes an ordinary plaintext copy only to the destination chosen by the user.

## Restore and recovery

- **Folder on Phone vaults**: Reopen anytime via **Select Existing Vault on Phone** -> pick the vault directory containing `vault.encrypz`.
- **Google Drive vaults**: Sign in with the same Google account, open **Open Existing Google Drive Vault** to scan Drive for `vault.encrypz`, and unlock with your master password or **Recovery Key**.
- **Forgotten Master Password**: Tap **Recover with Recovery Key** on a vault card or unlock screen to reset the master password offline without re-encrypting files.

## Current limitations

- File imports currently support up to **30 files per batch** and **15 MiB per file**.
- App-private **Phone Storage** vaults are removed if the app is uninstalled or app data is cleared; use **Folder on Phone**, **Google Drive**, or export backups to retain data across uninstalls.

## Testing

Run the Android JVM unit tests:

```bash
./Encrypz.Android/gradlew -p Encrypz.Android :app:testDebugUnitTest
```

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| Wrong master password on unlock | Verify spelling or use **Recover with Recovery Key** to set a new master password offline. |
| `UNREGISTERED_ON_API_CONSOLE` or Google error 10 | Register `com.encrypz.privacy` and the APK/Play App Signing SHA-1 fingerprint in Google Cloud Credentials and enable the **Google Drive API**. |
| `403: access_denied` during Drive OAuth | Add the Google account to **Test users** while the OAuth consent screen is in Testing. |
| `Error 400: redirect_uri_mismatch` | Add `http://localhost:5207/api/Auth/google-callback` under **Authorised redirect URIs** on the Web OAuth client. |
| APK refuses to install | Uninstall any existing `com.encrypz.privacy` signed with a different certificate (e.g., Play Store vs local upload key) before installing. |

## Contributing and license

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening pull requests. Never commit passwords, OAuth secrets, keystores, recovery keys, or user files.

Licensed under the [GNU Affero General Public License v3.0](LICENSE).
