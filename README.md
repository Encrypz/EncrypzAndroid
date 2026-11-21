# Encrypz

Encrypz is an encrypted personal file vault for Android (v2.0.0) that runs **100% locally on your device** with zero external database or account server required. Create **Local Device (Offline)** vaults or choose a **Folder on Phone** that works completely offline, or optionally create **Google Drive** vaults that sync directly with your Google Drive using the **Encrypz Vault v2 (`EPZ2`)** format.

This repository also contains a React web client and its supporting .NET backend for browser OAuth sessions. The Android and web clients currently use different Drive storage layouts and flows; they should not be treated as interchangeable clients for the same vault.

## Contents

- [Features](#features)
- [Getting started as a user](#getting-started-as-a-user)
- [Architecture and repository layout](#architecture-and-repository-layout)
- [Requirements](#requirements)
- [Google configuration](#google-configuration)
- [Web & API configuration](#web--api-configuration)
- [Android setup and APK builds](#android-setup-and-apk-builds)
- [Web development](#web-development)
- [Heroku deployment](#heroku-deployment)
- [Security and storage](#security-and-storage)
- [Restore and recovery](#restore-and-recovery)
- [Current limitations](#current-limitations)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Contributing and license](#contributing-and-license)

## Features

### Android vault (100% Local & Database-Free)

- **Multi-Vault Manager**: Create and manage multiple **Phone Storage (Offline)** or **Folder on Phone (Offline)** vaults directly on your phone (no Google sign-in, server, or database required), as well as optional **Google Drive** synced vaults.
- **Encrypz Vault v2 (`EPZ2`) Architecture**:
  - `masterkey.encrypz`: `encrypz.key.v2` JSON with 256-bit primary + 256-bit HMAC master keys wrapped with RFC 7914 `scrypt` (`N=32768`, `r=8`, `p=1`, 16-byte random salt), RFC 3394 AES Key Wrap, and KDF-bound `HMAC-SHA256` integrity MAC.
  - `vault.encrypz`: `encrypz.vault.v2` authenticated JSON manifest (`cipherSuite = ENCRYPZ_AES256_SIV_GCM_V2`) signed with `HMAC-SHA256` under the unwrapped masterkey.
  - `vault/XX/YYYY...`: SHA-256 directory sharding with domain-separated RFC 5297 AES-256-SIV `.epz` / `.epzl` encrypted filenames.
  - `EPZ2` 68-byte binary header + chunked **64 KiB** `AES-256-GCM` file content encryption with per-file content keys and header/chunk AAD authentication.
  - **Base32 Recovery Key**: Offline CRC-16/CCITT checksummed recovery key generated at vault creation to reset forgotten master passwords without any server.
- Select up to **30 files per batch** (15 MiB per file); each selected file is encrypted on-device.
- Browse files, create encrypted folders, search, and filter by file category.
- Preview supported images, videos, documents, and text; create encrypted text notes.
- Export a decrypted copy through Android's destination picker.
- Move items to a recycle bin and restore them before permanent deletion.

### Photos

- Three-column gallery grouped by original creation date, newest first.
- Search and All / Photos / Videos filters.
- Full-screen photo viewer with swiping, pinch zoom, and double-tap zoom.
- Three-dot menu for download, folder move, details, and recycle-bin actions.

### Access and notifications

- Offline master-password unlock on your phone; optional Google account selection and direct Drive OAuth when using Google Drive vaults.
- Quick unlock with **exactly six numeric digits** (leading zeroes supported) or device biometrics.
- Immediate vault locking when the activity goes into the background; returning requires unlocking again.
- Change Master Password (re-wraps `masterkey.encrypz` without re-encrypting files) and Recovery Key password reset.
- Screenshot protection (`FLAG_SECURE`) enabled by default, configurable in Security.
- In-app notification inbox with unread count and individual / all **Mark as read** actions.
- Android system notifications limited to upload and download outcomes.

## Getting started as a user

1. Install `encrypz-android-v2.0.0.apk` (or deploy `encrypz-android-v2.0.0.aab`).
2. Tap **Create New Vault** (or **Select Existing Vault on Phone**) and choose **Phone Storage**, **Folder on Phone**, or **Google Drive**.
3. Enter a vault name and strong master password (12+ characters).
4. Save your **Base32 Recovery Key** offline — there is no server or password-reset email.
5. Optionally set a six-digit Quick-Unlock PIN or fingerprint unlock.
6. Add files or folders; everything is encrypted client-side on your Android device before saving locally or syncing directly to Google Drive.

Install updates over the existing app using the same package and signing certificate. Uninstalling removes app-private storage vaults (`LOCAL`), while **Folder on Phone** and **Google Drive** vaults remain intact.

## Architecture and repository layout

```text
Android app (Database-Free · Zero Server)
  |-- Phone Storage / Folder -> On-device storage (vault.encrypz, masterkey.encrypz, vault/XX/YYYY)
  `-- Google Drive vaults ---> Google Drive REST v3 directly (vault.encrypz, masterkey.encrypz, vault/XX/YYYY)

React web client --> .NET API --> Google Drive hidden appDataFolder
```

| Path | Purpose |
| --- | --- |
| `Encrypz.Android/` | Kotlin / Jetpack Compose Android application (`v2.0.0`, 100% on-device) |
| `Encrypz.API/` | ASP.NET Core API supporting the React web client OAuth & Drive endpoints |
| `Encrypz.Core/` | Domain models and interfaces |
| `Encrypz.Infrastructure/` | Drive storage and supporting infrastructure for the web client |
| `Encrypz.UI/` | React / TypeScript / Vite web application |
| `Encrypz.Tests/` | .NET tests |
| `Encrypz.Android/app/src/test/` | Android JVM unit tests |
| `Heroku.Dockerfile` | Combined web frontend and .NET API container build |
| `heroku.yml` | Heroku container build and web process definition |
| `.env.example` | Web/API OAuth configuration template; contains placeholders only |

Android application ID: **`com.encrypz.privacy`**.

## Requirements

| Component | Requirement in this repository |
| --- | --- |
| Android device | Android 7.0 / API 24 or newer (offline vaults work without internet; Google Drive vaults require Google Play services) |
| Android tooling | Android Gradle Plugin 9.0.1 compatible Android SDK; platform 36 |
| Java | JDK 17 toolchain configured for the Android build |
| Gradle | Use the included wrapper |
| Web backend | .NET 8 SDK / runtime (only needed for the web frontend) |
| Web frontend | Node.js 24 and npm (only needed for the web frontend) |

Dependency versions are maintained in [the Android version catalog](Encrypz.Android/gradle/libs.versions.toml), [the Android build](Encrypz.Android/app/build.gradle.kts), and [the web package manifest](Encrypz.UI/package.json).

## Google configuration

*(Only needed when building with Google Drive vault sync enabled; Phone Storage and Folder on Phone vaults require no Google Cloud project.)*

1. Select or create a Google Cloud project and enable the **Google Drive API**.
2. Configure the OAuth consent screen and add test users while in Testing.
3. Create an Android OAuth client for package **`com.encrypz.privacy`** matching your APK's **SHA-1** signing fingerprint.
4. Create/configure a Web OAuth client if using direct loopback Drive OAuth or the React web client (`http://localhost:5207/api/Auth/google-callback`).

## Web & API configuration

*(Optional — Android runs standalone without this API.)*

Run these commands from the repository root if running the React web client locally:

```bash
cp .env.example .env
dotnet restore Encrypz.API/Encrypz.API.csproj
```

| Setting | Purpose |
| --- | --- |
| `GoogleDrive__ClientId` | Backend Google OAuth web client ID (for the React web app) |
| `GoogleDrive__ClientSecret` | Backend OAuth web client secret (for the React web app) |
| `Frontend__Url` | Public browser frontend origin |
| `Frontend__AllowedOrigins` | Allowed browser origins |
| `PORT` | Supplied by Heroku in hosted web deployments |

Start the API (`GET /health` and `GET /health/ready` check process health; no database is used):

```bash
dotnet run --project Encrypz.API --launch-profile http
```

## Android setup and APK builds

Open **`Encrypz.Android`** in Android Studio or build from the command line:

```bash
cd Encrypz.Android
./gradlew :app:testDebugUnitTest :app:bundleRelease :app:assembleRelease
```

Release outputs:

- `Encrypz.Android/app/build/outputs/bundle/release/app-release.aab`
- `Encrypz.Android/app/build/outputs/apk/release/app-release.apk`

With `Encrypz.Android/signing.properties` configured, both `.aab` and `.apk` are signed using the release upload keystore (`CN=Encrypz Upload, O=Encrypz`).

## Web development

From the repository root:

```bash
npm ci --prefix Encrypz.UI
npm run dev --prefix Encrypz.UI
```

## Heroku deployment

`heroku.yml` builds `Heroku.Dockerfile` to serve the React frontend and `.NET` web API together:

- Set `GoogleDrive__ClientId`, `GoogleDrive__ClientSecret`, `Frontend__Url`, and `Frontend__AllowedOrigins` in Heroku Config Vars.
- Verify `/health` and `/health/ready` after deployment.

## Security and storage

- All vault keys, `scrypt` password derivation, `AES-256-SIV` filenames, and **64 KiB** `EPZ2` `AES-256-GCM` chunk encryption execute strictly on the user's device.
- No password, plaintext filename, or master key ever leaves the device or touches any Encrypz server or database.
- Android Keystore (`DeviceSecrets`) seals cached session material used for biometric/PIN quick unlock. Android cloud auto-backup is disabled in the manifest.
- Decrypted exports are ordinary plaintext files in the destination you choose.

## Restore and recovery

- **Phone Storage / Folder on Phone vaults**: Remain on the phone; **Folder on Phone** vaults can also be reopened later via **Select Existing Vault on Phone**.
- **Google Drive vaults**: Sign in with the same Google account, open **Open Existing Google Drive Vault** to scan Drive for `vault.encrypz`, and unlock with your master password or **Recovery Key**.
- **Forgotten Master Password**: Use **Recover with Recovery Key** on the unlock screen or vault menu to reset the password offline without re-encrypting vault files.

## Current limitations

- **Android and web storage differ**: Android uses Encrypz Vault v2 (`EPZ2`) folders locally or in Google Drive; the web client uses hidden `appDataFolder`.
- **Android imports support up to 30 files per batch and 15 MiB per file.**
- **App-private Phone Storage vaults** are deleted if you uninstall the app or erase app data; choose **Folder on Phone**, **Google Drive**, or export copies to preserve data across uninstalls.

## Testing

Android unit tests:

```bash
./Encrypz.Android/gradlew -p Encrypz.Android :app:testDebugUnitTest
```

`.NET` tests:

```bash
dotnet test Encrypz.Tests/Encrypz.Tests.csproj
```

Web tests & build:

```bash
npm test --prefix Encrypz.UI && npm run build --prefix Encrypz.UI
```

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| Wrong master password on unlock | Verify spelling or use **Recover with Recovery Key** to set a new master password offline. |
| `UNREGISTERED_ON_API_CONSOLE` or Google error 10 | Verify `com.encrypz.privacy`, the signing SHA-1 fingerprint, OAuth client, and enabled Google Drive API. |
| `403: access_denied` / app is being tested | Add the signed-in Google account as a test user on your Google Cloud OAuth consent screen. |
| Drive permission not granted | Grant Drive permission on the Google Drive vault screen or in the Security tab. |
| APK refuses to install | Uninstall any existing `com.encrypz.privacy` signed with a different certificate before installing the release APK. |
| Old PIN no longer works | Use **Use master password instead**, then configure a new 6-digit PIN in Security. |

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidance. Never include passwords, OAuth tokens, recovery keys, or private user files in issue reports.

The repository includes the [GNU Affero General Public License v3.0](LICENSE). Refer to that file for the complete license terms.
