# Android release

The application ID and source namespace are `com.encrypz.privacy`.

Build the signed bundle with JDK 17 or a compatible Android Studio JDK:

```powershell
.\gradlew.bat :app:bundleRelease :app:testDebugUnitTest
```

Output: `app/build/outputs/bundle/release/app-release.aab`.

Release signing reads the local `signing.properties` file. See
`signing.properties.example` for the expected keys. Passwords can alternatively
be provided through `ENCRYPZ_STORE_PASSWORD` and `ENCRYPZ_KEY_PASSWORD`.

The initial upload key is `signing/upload-keystore.jks`, with alias
`encrypz-upload`. Back up this key and `signing.properties` securely; they are
excluded from Git and are needed to sign future uploads. Do not regenerate the
key for subsequent releases. Increment `versionCode` for each new Play upload.

Configure the Google Android OAuth client for `com.encrypz.privacy` and the
certificate fingerprint used to sign installed apps. For Google Play installs,
use the app signing certificate from Play Console's app integrity settings.
The upload certificate is exported to `signing/upload-certificate.pem`.
