# BedrockPocket · Minecraft Bedrock Dedicated Server for Android

BedrockPocket runs **Mojang's official Bedrock Dedicated Server (BDS)** on an ARM64 phone through Ubuntu, PRoot, and Box64. It does not use Altay or PocketMine. The current app version is 0.8.43.

## Runtime design

Mojang's Linux BDS binary is x86_64 and requires Ubuntu 22.04 LTS or later. The app prepares an Ubuntu 26.04.1 ARM64 userspace, uses PRoot for Android, installs Box64 and the x86_64 runtime libraries from Ubuntu's package archive, then starts the official BDS binary through Box64. All runtime files stay in app-private storage; Termux is not required.

The first setup downloads the Ubuntu base and Mojang BDS, then installs Box64 packages through Ubuntu APT. Allow at least 2.5 GB of free phone storage and accept the Minecraft EULA in the app before setup. Keep the phone powered and the app in the foreground during installation. Android may still restrict long-running background servers depending on the device's battery settings.

The app keeps existing server files as the default profile and supports additional isolated profiles, one active BDS process at a time. Scheduled local backups run only while the server is stopped; when an alarm fires during a run, the app reports and retries later. The Console supports text search, basic severity/type filters, log clearing, and export.

## Source and integrity checks

- BDS: Mojang HTTPS download, version `1.26.61.1`; SHA-256 is pinned in `ServerInstaller.java` and checked before extraction.
- Ubuntu rootfs: Canonical Ubuntu Base 26.04.1 ARM64; SHA-256 is checked against Canonical's published `SHA256SUMS` value.
- PRoot: Android ARM64 build from the public `green-green-avk/build-proot-android` source project; the archive is bundled in the app and its MIT license is included in assets.
- Box64 and x86_64 system libraries: installed from the Ubuntu 26.04 package archive using Ubuntu's signed APT metadata.

## Build

Open the project in Android Studio with Android SDK Platform 35, Build Tools 35.0.0, and JDK 17 or newer. Build the debug APK with:

    gradlew.bat assembleDebug

## Verification status

The 0.8.43 APK was installed over the existing app on a Samsung SM-G935K (Android API 26). The TPS pack embedded in the APK was enabled for the current world and the app confirmed installation. On this device, BDS 1.26.61.1 launched with `online-mode=true`, created the world, bound port 19132, and signed in to the signaling service, but then failed to connect to Minecraft services and stopped before accepting players. Secure-mode player connectivity is therefore not verified on this device. Playit, scheduled backups, profile isolation, Google Drive API sync, and LAN Dashboard access are also not fully verified end to end.

Direct Google Drive API sync still needs an OAuth client configured for the app. The existing Android Document Picker can save a backup to a user-selected Drive provider but does not provide scheduled OAuth sync.
