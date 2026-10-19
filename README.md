# Encrypz

Encrypz is an encrypted personal file vault for Android, with automatic Google Drive backup and a .NET account service. Encrypt files on your device, browse photos and videos, and restore uploaded files after reinstalling the app by signing in with the same Google account and master password.

This repository also contains a React web client. The Android and web clients currently use different Drive storage layouts and account flows; they should not be treated as interchangeable clients for the same vault.

## Contents

- [Features](#features)
- [Getting started as a user](#getting-started-as-a-user)
- [Architecture and repository layout](#architecture-and-repository-layout)
- [Requirements](#requirements)
- [Google configuration](#google-configuration)
- [Backend configuration](#backend-configuration)
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

### Android vault

- Encrypt file contents locally with AES-256-GCM before uploading.
- Select up to **30 files per batch**; each selected file is encrypted and queued for upload.
- Automatically upload encrypted `.enc` files to the **Encrypz** folder in Google Drive when authorization and connectivity are available.
- Encrypt uploaded filenames and preserve creation dates in encrypted Drive metadata for new uploads.
- Restore uploaded files using the original account and vault key.
- Start new accounts with an empty vault, without sample files.
- Browse files, create local folders, search, and filter by file category.
- Preview supported images, videos, documents, and text; create encrypted text notes.
- Export a decrypted copy through Android's destination picker.
- Move items to a recycle bin and restore them before permanent deletion.

### Photos

- Three-column gallery grouped by original creation date, newest first.
- Search and All / Photos / Videos filters.
- Full-screen photo viewer with swiping, pinch zoom, and double-tap zoom.
- Three-dot menu for download, folder move, details, and recycle-bin actions.
- No item-count or "By upload date" summary above the gallery.

### Access and notifications

- Google account selection and Drive authorization during setup, with a permission action in Security for retrying authorization.
- Master-password account creation and verification through the backend.
- Quick unlock with **exactly six numeric digits**. Leading zeroes are supported.
- Immediate vault locking when the activity goes into the background; returning requires unlocking again. Rotation alone does not intentionally lock the vault.
- Master-password fallback when a PIN is unavailable or forgotten.
- Screenshot protection enabled by default, configurable in Security.
- In-app notification inbox accessed through the notification icon, with unread count and individual / all **Mark as read** actions.
- Android system notifications limited to upload and download outcomes. Other status messages stay in the in-app inbox.

## Getting started as a user

1. Install an APK built for your configured backend and Google project.
2. Select your Google account and complete Google's Drive consent screen.
3. Create an Encrypz account with a strong master password, or sign in to an existing account.
4. Set a six-digit PIN for quick unlock on this installation.
5. Add a file. Encrypz encrypts it locally and attempts to upload it automatically to Drive.
6. Confirm that the upload succeeded before removing the original file or uninstalling the app.
7. Use the Photos tab to browse media or the Files tab to organize files.

Google consent and Android notification permission are separate. Granting notification permission does not grant Drive access. A revoked or expired Google authorization may require consent again; the app cannot bypass Google's permission requirements.

Install updates over the existing app using the same package and signing certificate. Uninstalling removes local state, including files that have not successfully uploaded.

## Architecture and repository layout

```text
Android app
  |-- Google authorization and encrypted file uploads --> Google Drive / Encrypz
  |-- HTTPS account lookup, registration, login --------> .NET API
  |                                                       |-- Google identity verification
  |                                                       `-- MongoDB / encrypz.mobile_accounts
  `-- Local encrypted files and Keystore-protected device secrets

React web client --> .NET API --> Google Drive hidden appDataFolder
```

| Path | Purpose |
| --- | --- |
| `Encrypz.Android/` | Kotlin / Jetpack Compose Android application |
| `Encrypz.API/` | ASP.NET Core API, mobile account service, browser endpoints |
| `Encrypz.Core/` | Domain models and interfaces |
| `Encrypz.Infrastructure/` | Drive storage and supporting infrastructure |
| `Encrypz.UI/` | React / TypeScript / Vite web application |
| `Encrypz.Tests/` | .NET tests |
| `Encrypz.Android/app/src/test/` | Android JVM unit tests |
| `Heroku.Dockerfile` | Combined web frontend and .NET API container build |
| `heroku.yml` | Heroku container build and web process definition |
| `.env.example` | Backend configuration template; contains placeholders only |

Android application ID: **`com.encrypz.vault`**. The source namespace remains `com.example.encrypz`; register the application ID, not the source namespace, in Google Cloud.

## Requirements

| Component | Requirement in this repository |
| --- | --- |
| Android device | Android 7.0 / API 24 or newer, with Google services for the current authorization flow |
| Android tooling | Android Studio compatible with Android Gradle Plugin 9.0.1; SDK platform 36 |
| Java | JDK 17 toolchain configured for the Android build |
| Gradle | Use the included wrapper; do not substitute a global Gradle installation |
| Backend | .NET 8 SDK for development; .NET 8 runtime in the deployment image |
| Web / combined container | Node.js 24 and npm |
| Accounts | Google Cloud project with Drive API enabled; MongoDB for Android accounts |
| Hosting | Public HTTPS endpoint reachable from the Android device |
| Heroku deployment | Heroku account, CLI, Git, and an enabled web dyno plan |

Dependency versions are maintained in [the Android version catalog](Encrypz.Android/gradle/libs.versions.toml), [the Android build](Encrypz.Android/app/build.gradle.kts), and [the web package manifest](Encrypz.UI/package.json).

## Google configuration

1. Select or create the Google Cloud project used by your deployment.
2. Enable the **Google Drive API**.
3. Configure the OAuth consent screen / Google Auth Platform branding, audience, and requested scopes.
4. While the project is in Testing, add the Google accounts that will use the app as test users. See [Google's audience settings](https://support.google.com/cloud/answer/15549945).
5. Create an Android OAuth client with package **`com.encrypz.vault`** and the **SHA-1 of the certificate that signs the installed APK**. Google uses this pair to identify the Android client. See [Android client authentication](https://developers.google.com/android/guides/client-auth).
6. Create/configure the web OAuth client needed by the API's browser flow and set its client ID and secret on the backend.

From `Encrypz.Android`, inspect signing fingerprints with:

```powershell
.\gradlew.bat signingReport
```

Debug, independently signed release, and Play-distributed builds may have different signing certificates. Configure each intended certificate. For Play App Signing, use the app-signing certificate used for the installed application.

For the browser client, register the exact callback URLs for your environments:

```text
http://localhost:5207/api/Auth/google-callback
https://YOUR_API_HOST/api/Auth/google-callback
```

The Android Drive integration uses `drive.file`; the web vault uses hidden application data with `drive.appdata` and requests `drive.file` for legacy import. These storage locations differ. See [Drive application data](https://developers.google.com/workspace/drive/api/guides/appdata).

## Backend configuration

Run these commands from the repository root:

```powershell
Copy-Item .env.example .env
dotnet restore Encrypz.API/Encrypz.API.csproj
```

Edit `.env` locally. Never commit real credentials:

```dotenv
GoogleDrive__ClientId=YOUR_WEB_OAUTH_CLIENT_ID
GoogleDrive__ClientSecret=YOUR_WEB_OAUTH_CLIENT_SECRET
Frontend__Url=http://localhost:5173
Frontend__AllowedOrigins=http://localhost:5173
MongoDB__ConnectionString=mongodb+srv://USERNAME:URL_ENCODED_PASSWORD@YOUR_CLUSTER.mongodb.net/?retryWrites=true&w=majority
```

| Setting | Purpose |
| --- | --- |
| `GoogleDrive__ClientId` | Backend Google OAuth web client ID |
| `GoogleDrive__ClientSecret` | Backend-only OAuth web client secret |
| `Frontend__Url` | Public browser frontend origin used by the deployment |
| `Frontend__AllowedOrigins` | Allowed browser origins; use the exact deployed frontend origin for a single-origin setup |
| `MongoDB__ConnectionString` | Backend-only MongoDB connection URI for mobile accounts |
| `PORT` | Supplied by Heroku; the API binds to this port in hosted deployments |

Configure a database user with the required access and allow network connectivity from the backend in your MongoDB deployment. URL-encode special characters in URI credentials. The current service uses the fixed database `encrypz` and collection `mobile_accounts`; it can also read compatible legacy records in `vault_users`.

Start the API:

```powershell
dotnet run --project Encrypz.API --launch-profile http
```

Android account requests require HTTPS. The local HTTP API is suitable for the browser development proxy; Android testing needs a trusted HTTPS development endpoint or a deployed backend. A phone's `localhost` is the phone, not your development computer.

### Health and mobile account routes

| Method / route | Purpose |
| --- | --- |
| `GET /health` | Process liveness |
| `GET /health/ready` | Account-store readiness, including MongoDB connectivity |
| `GET /api/mobile/accounts` | Authenticated account lookup and credential metadata |
| `POST /api/mobile/accounts/register` | Register an account with password-derived proof and wrapped vault key |
| `POST /api/mobile/accounts/login` | Verify proof and return the original wrapped vault key |

Mobile account endpoints require a valid Google bearer token. Email supplied by a client is not trusted as identity. Credential endpoints have rate limits and do not return database connection details in errors. Registration and login payloads contain derived values, not the plaintext master password; use the Android client implementation rather than inventing your own credential format.

## Android setup and APK builds

Open **`Encrypz.Android`** in Android Studio and allow Gradle to sync.

Create or update `Encrypz.Android/local.properties`. Preserve the `sdk.dir` entry generated by Android Studio and add:

```properties
ACCOUNT_API_BASE_URL=https://api.encrypz.com
GOOGLE_CLIENT_ID=YOUR_GOOGLE_CLIENT_ID
```

`api.encrypz.com` is the project's configured API domain; substitute your own reachable HTTPS origin when running a separate deployment. Do not append `/api/mobile/accounts`: the client constructs endpoint paths. These build settings can also come from environment variables when the corresponding local property is absent.

Do **not** put a MongoDB URI, database password, or Google client secret in Android build configuration. APK contents can be inspected. The native authorization path does not require an embedded client secret.

From `Encrypz.Android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

On macOS / Linux, use `./gradlew` instead of `.\gradlew.bat`.

The release APK is generated at:

```text
Encrypz.Android/app/build/outputs/apk/release/app-release.apk
```

For a debug build, run `:app:assembleDebug`. With a device connected through ADB:

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

The repository's release configuration currently uses the **debug signing key** and has minification disabled. Configure a protected release keystore, register its OAuth fingerprint, and increment the app version code before distributing a production release. Changing signing keys prevents a normal in-place update of an existing installation.

Locally distributed copies may be named `encrypz-android-v1.0.0.apk` in the repository root and Android directory. APKs are ignored by Git; building from a clone produces the output above.

## Web development

The web client uses a separate Drive-based vault layout. Its normal vault flow does not require MongoDB, but the Android account endpoints do.

From the repository root:

```powershell
npm ci --prefix Encrypz.UI
npm run dev --prefix Encrypz.UI
```

Run the API in another terminal and open `http://localhost:5173`. Use the configured hostname consistently so browser origins and OAuth callbacks match.

The browser generates and wraps its vault key locally and stores encrypted vault data in Drive's hidden `appDataFolder`. Browser server sessions are temporary and expire on server restart. The current in-memory session design is intended for one API instance; scaling browser sessions across instances requires additional session architecture.

## Heroku deployment

The included container build compiles the React frontend, publishes the .NET API, and serves both from one web process. `heroku.yml` selects `Heroku.Dockerfile`. Follow [Heroku's container build documentation](https://devcenter.heroku.com/articles/build-docker-images-heroku-yml).

Use a separate app for this backend when an existing Heroku app hosts another service. Replace `YOUR_APP_NAME` in the examples:

```powershell
heroku login
heroku create YOUR_APP_NAME --stack container
heroku git:remote -a YOUR_APP_NAME
```

If the intended app already exists, skip creation and configure it:

```powershell
heroku stack:set container -a YOUR_APP_NAME
heroku git:remote -a YOUR_APP_NAME
```

In the Heroku dashboard's **Settings > Config Vars**, set:

- `GoogleDrive__ClientId`
- `GoogleDrive__ClientSecret`
- `MongoDB__ConnectionString`
- `Frontend__Url`
- `Frontend__AllowedOrigins`

Use your deployment's public HTTPS browser origin for the frontend values. Do not copy local `.env` files into the image or commit secrets to deploy them.

After committing the intended source changes:

```powershell
git push heroku main
```

Enable a web dyno using the hosting plan you have chosen. Selecting a paid plan incurs the provider's charges. Check the release and logs:

```powershell
heroku ps -a YOUR_APP_NAME
heroku logs --tail -a YOUR_APP_NAME
```

Verify both health endpoints, substituting your deployment's hostname:

```powershell
Invoke-RestMethod https://YOUR_API_HOST/health
Invoke-RestMethod https://YOUR_API_HOST/health/ready
```

A successful `/health` response alone does not prove that MongoDB, Google authorization, or uploads work.

### Custom domain

Add your API domain to the intended Heroku app, configure the DNS target provided by Heroku, and enable/provision HTTPS for the domain. Once HTTPS works, use that origin in `ACCOUNT_API_BASE_URL` and rebuild the APK. If the browser frontend uses the new origin, update frontend settings and the Google callback URL too.

Changing a backend URL in hosting configuration does not change an APK that has already been built.

## Security and storage

### Android account and file protection

- File encryption uses AES-256-GCM with authenticated ciphertext and a random vault key.
- New account password wrapping uses PBKDF2-HMAC-SHA-256 with 600,000 iterations and a random salt.
- The client derives a distinct account proof. The backend stores a separately salted PBKDF2 hash of that proof, the password salt, and the encrypted/wrapped vault key.
- MongoDB stores Google identity, email, credential metadata, and wrapped key material. It does not store uploaded file contents or plaintext master passwords.
- Android Keystore protects device secrets used by local persistence. Android application backup is disabled in the manifest.
- A PIN is a local quick-unlock mechanism. It is not a replacement for the master password when restoring on a new device.
- Background locking blocks access through the UI. It is not a guarantee that all decrypted data is immediately erased from process memory.
- Original creation timestamps stored for new Drive uploads are encrypted in app-specific metadata.

Google Drive still sees encrypted file sizes, IDs, folder placement, and service timestamps. Encrypting a filename does not hide all traffic or storage metadata. A decrypted export is an ordinary unencrypted file in the destination you select.

The current implementation has not been established by this README as independently audited. Keep credentials out of Git, logs, screenshots, and APKs. Rotate any credential that has been exposed. Keep independent backups of important data and protect backend database backups because account records contain material required to restore vault access.

## Restore and recovery

### Reinstalling Android or signing in on another device

1. Install a correctly configured build.
2. Select the **same Google account** used for the uploads.
3. Sign in with the **original master password**.
4. Grant or renew Drive access if requested.
5. Allow synchronization to complete, or use the Drive sync/import action.
6. Create a new local six-digit PIN as needed.

The account API returns the original wrapped vault key after verification; the device unwraps it and decrypts files downloaded from Drive. Compatible legacy account records can be adopted after Google ownership and credential verification. Creating a different account or generating a replacement key cannot decrypt old ciphertext.

If an older installation uses a four- or five-digit PIN, use **Use master password instead**, then set a six-digit PIN in Security.

### Recovery boundaries

- Files that never uploaded cannot be restored from Drive.
- A PIN does not restore an account after reinstalling.
- A forgotten master password cannot be recovered from ciphertext alone.
- Old files whose original vault key was never backed up cannot be recovered by a new login.
- Browser configuration backups belong to the browser vault flow; do not assume they restore Android vaults.

## Current limitations

- **Android and web storage are separate.** Android uses the visible `Encrypz` folder; the web client uses `appDataFolder`.
- **Folder organization and recycle-bin flags are local on Android.** Restored files may appear in the root rather than their former local folders.
- **Permanent deletion depends on Drive access.** Individual file deletion and emptying the recycle bin attempt remote deletion; failed remote deletion can leave a copy available for later synchronization. Local deletion messages are not proof of secure physical erasure.
- **Creation dates are best effort.** Embedded photo/video dates take priority. Where absent, available provider metadata or saved upload dates are used. Some old files do not contain enough metadata to recover their original creation date.
- **Android imports support up to 30 files per batch and 15 MiB per file.** Larger files are rejected without saving partial content. A failed file does not stop the rest of the batch.
- **Google authorization is required.** Upload attempts do not intentionally prompt for consent each time; denied access must be repaired through login or Security.
- **PIN setup remains optional.** If no PIN is configured, use the master password to unlock. Any PIN that is configured must be six digits.
- **A deployed account service is required for Android account creation and cross-device verification.** A MongoDB connection string alone is not an Android backend.
- **Release signing needs production configuration.** The checked-in release build currently signs with the debug key.

## Testing

Run only the checks relevant to your change, from the indicated directory.

Android, from `Encrypz.Android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

Backend, from the repository root:

```powershell
dotnet test Encrypz.Tests/Encrypz.Tests.csproj
```

Web, from the repository root:

```powershell
npm test --prefix Encrypz.UI
npm run build --prefix Encrypz.UI
```

Before distributing an APK, also verify on a device:

1. Account creation, existing-account login, and Drive consent.
2. Rejection of four-/five-digit PINs and successful six-digit PIN unlock.
3. Home, app switching, and return to the locked screen.
4. Import and export through Android pickers, including re-authentication after leaving the app.
5. Upload completion and restoration on a clean test installation using a disposable test account.
6. Photo ordering, zoom, paging, folder moves, and recycle-bin behavior.
7. Upload/download system notifications and in-app mark-as-read actions.

Unit tests and a successful APK build do not verify Google Cloud configuration, live Drive access, or device lifecycle behavior.

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| `UNREGISTERED_ON_API_CONSOLE` or Google error 10 | Verify `com.encrypz.vault`, the installed APK's signing SHA-1, OAuth project, and enabled Drive API. Rebuilding with another signing key requires matching Google configuration. |
| `403: access_denied` / app is being tested | Add the signed-in account as a test user in the correct Google project, or complete the production publishing requirements for your app. |
| Drive permission not granted | Complete Google's consent screen using the intended account. Retry from Security; check that the correct OAuth project and scopes are configured. |
| No Encrypz folder | Confirm successful Drive authorization and folder creation. Verify the Google account, connectivity, API enablement, and authorization error shown by the app. |
| Account server is not deployed yet | Set a reachable HTTPS `ACCOUNT_API_BASE_URL` and rebuild/reinstall the APK. |
| Account service unavailable | Check `/health/ready`, MongoDB configuration, database network access, and backend logs. |
| Files missing after reinstall | Use the original Google account and master password, verify previous uploads completed, then sync. Local-only files and folder arrangements do not automatically restore. |
| Old PIN no longer works | Use the master password and replace the old PIN with exactly six digits. |
| Dates differ from the original | The file/provider may lack creation metadata; the app uses a fallback date. |
| Update fails to install | Verify package ID, version code, and signing certificate. Avoid uninstalling before protecting any unsynced data. |
| Notifications are absent | Check Android notification permission and channel settings. Non-transfer messages intentionally stay in the in-app inbox. |

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidance. Include relevant test results and distinguish local behavior from Google/backend behavior when reporting issues. Never include passwords, OAuth tokens, database URIs, or private user files in issue reports.

The repository includes the [GNU Affero General Public License v3.0](LICENSE). Refer to that file for the complete license terms.
