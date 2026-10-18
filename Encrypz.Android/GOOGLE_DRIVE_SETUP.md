# Google Drive setup for this APK

Automatic uploads use Android Google Identity Services with the `drive.file` scope.
A Web OAuth client ID and secret do not configure Android authorization.

In the Google Cloud project used by this app:

1. Enable the Google Drive API.
2. Configure an Android OAuth client with package `com.example.encrypz` and this APK signing SHA-1:
   `38:C0:58:6C:65:10:A2:FB:3C:20:29:E1:0B:D7:4F:88:BC:DD:8F:DB`.
3. Configure the consent screen. If the app is in Testing, add the Google account as a test user.

The fingerprint above comes from `:app:signingReport`; both current build variants use the local debug signing key. Register the appropriate fingerprint again if the signing key changes.

Install the rebuilt APK over the existing app, then log in. Allow Drive access when Google asks. The app verifies or creates `Encrypz` in My Drive, retries queued encrypted files after unlock, and automatically uploads new files. No per-file permission prompt or Save button is needed.

A failed setup now displays the actual authorization or Drive API error. Error 10 indicates Android OAuth configuration needs checking. Drive API errors such as API disabled, insufficient permissions, quota exceeded, and network failures are displayed rather than reported as success.

No live account upload was verified during this build: no Android device was connected.

Reference: https://developer.android.com/identity/authorization
