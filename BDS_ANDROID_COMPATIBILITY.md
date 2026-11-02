# Official BDS on Android: compatibility check

## Result

The official Linux Bedrock Dedicated Server package is not a native Android ARM64 executable. Its main `bedrock_server` file is an ELF 64-bit x86_64 binary (ELF machine ID 62). BedrockPocket uses an experimental ARM64 Ubuntu + PRoot + Box64 route. It can launch the process on the tested phone, but secure-mode player connectivity did not succeed in the latest device test; Android is not an officially supported BDS host.

## Package checked

- Version: `1.26.61.1` (stable Linux version reported by the Bedrock-OSS BDS-Versions index on 2026-10-30)
- Download: [Mojang official Linux ZIP](https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.61.1.zip)
- Local file: `runtime-downloads/bedrock-server-1.26.61.1-linux.zip`
- Size: 98,093,051 bytes
- SHA-256 computed after download: `b54095267d55fd401de42f71ac4daf2c1747a49515ad41810781f1f9bafde26e`
- Main executable: `bedrock_server`, 265,500,488 bytes, ELF64, little-endian, machine ID 62 (`x86_64`)

The ZIP came from Mojang's HTTPS domain. The SHA-256 is a local integrity fingerprint; Mojang did not publish a checksum in the metadata used for this check.

## Runtime requirements and test limits

Mojang's [BDS download page](https://www.minecraft.net/en-us/download/server/bedrock) says Linux BDS requires Ubuntu 22.04 LTS or later. The app uses Canonical's Ubuntu 26.04.1 ARM64 base and Box64 from Ubuntu's signed APT repositories; PRoot is built for Android ARM64 by the public `green-green-avk/build-proot-android` project and its MIT license is included in the app.

The Ubuntu ARM64 base was downloaded from Canonical and matched its published SHA-256 `5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd`. The bundled PRoot archive was checked as an ARM64 Android build. Its SHA-256 is `9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281`.

Tested on an ARM64 Android API 26 device. Ubuntu/Box64 setup completed. BDS 1.26.61.1 launched with `online-mode=true`, created the world and logged `Accepting clients on [::]:19132`, then signed in to the signaling service. It later logged `Could not connect to Minecraft services. This is required to accept connections in online mode.` and stopped. Thus process launch and initial port bind were observed, but the server did not become available for secure-mode players. Compatibility through Box64/PRoot remains experimental; a successful process launch does not establish player connectivity.

## Next validation step

Install the new APK on the intended Android phone, keep it powered and in the foreground, accept the Minecraft EULA, install the runtime and BDS, then try a LAN connection on UDP port 19132. If setup or startup fails, preserve the app log and use the exact error to adjust the runtime before claiming the server works.
