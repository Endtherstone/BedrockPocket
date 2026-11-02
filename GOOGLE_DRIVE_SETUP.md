# Google Drive setup for BedrockPocket

Direct Drive API backup is not enabled yet. The app currently uses Android's document picker, which can save a backup through the Google Drive app, but it does not perform scheduled Drive sync.

To prepare the Google Cloud project for a future direct integration:

1. In Google Cloud Console, create or select a project and enable **Google Drive API**.
2. Configure the OAuth consent screen. For personal testing, use the testing audience and add the Google account that will test the app as a test user.
3. Add only the `https://www.googleapis.com/auth/drive.file` scope. This is the narrow per-file scope intended for files created by the app or explicitly shared with it.
4. Create an OAuth client ID of type **Android** with package `dev.codex.bedrockpocket` and SHA-1 fingerprint:

   ```text
   0B:2A:A3:BE:61:62:C5:BA:CE:BC:AD:86:D9:6D:8F:9F:C2:B5:42:18
   ```

   This fingerprint is from the locally signed test APK `BedrockPocket-0.8.36.apk`. A production APK signed with a different key needs a different Android OAuth client fingerprint.

5. Copy the generated OAuth client ID (the value ending in `.apps.googleusercontent.com`) for the later app integration. Do not send a client secret; Android OAuth clients do not use an embedded client secret.

Do not change the consent-screen app to production or request broader Drive scopes just for local testing. After direct integration is implemented, it should request authorization on demand, keep access tokens out of logs, and handle revoked/expired authorization by asking the user to reconnect.
