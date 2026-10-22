# Building

## Requirements

- Python 3.9 or newer.
- JDK 17 or newer, with `java`, `javac` and `keytool` on `PATH`, or pass `--java-home`.
- Android SDK platform **android-35** and build-tools **36.0.0** by default.
- Android NDK for a full native rebuild. The original native release libraries were built with **NDK r30**.

The app declares `minSdkVersion=26` and `targetSdkVersion=34`. Building against SDK platform 35 does not change its declared target SDK.

## Run tests without the Android SDK

```sh
python3 build.py --test-only
```

This runs descriptor, packet, DHCP, ARP, IPv4 and translation-resource checks. The GitHub Actions workflow runs the same command.

## Full source build

Set `ANDROID_HOME` to your SDK directory, then run:

```sh
python3 build.py --ndk /path/to/android-ndk-r30
```

PowerShell:

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
python build.py --ndk 'C:\Android\android-ndk-r30'
```

The script builds both `armeabi-v7a` and `arm64-v8a`, compiles Java and resources, aligns the APK, signs it, and verifies the result. Output is written to `dist/`; temporary files and local development keys stay in `.build/`. Both directories are ignored by Git.

Without `--keystore`, a **local development key** is generated. This is suitable for your own installations, but its APK cannot update an app signed with another certificate. Keep any key you use for future updates in a secure backup.

For an existing keystore, set `KEYSTORE_PASSWORD` in your environment and use:

```sh
python3 build.py --ndk /path/to/ndk --keystore /secure/path/project.p12 --alias rtl8153diagnostic
```

Passwords are passed to signing tools through an environment variable. The private official release key is not included in the repository or releases.

## Reuse the released native libraries

If you are editing Java code or resources without changing the native data path, you can reuse the verified libraries from the official release APK:

```sh
python3 build.py --native-apk /path/to/RTL8153-USB-Ethernet-0.6.1.apk
```

This path does not require the NDK. Both embedded libraries are checked against their known SHA-256 digests before use. It does not rebuild C++ changes; use `--ndk` for those.

To produce only an unsigned, aligned APK:

```sh
python3 build.py --native-apk /path/to/released.apk --unsigned
```

Unsigned APKs cannot be installed directly. Prebuilt official native libraries carry their retained notices from `licenses/`.

Additional options include `--sdk`, `--build-tools`, `--platform`, `--java-home`, `--password-env` and `--alias`. Run `python3 build.py --help` for details.

## On-device language test

`tests/LocaleResourceCheck.java` is a separate Android `app_process` test, not a desktop JVM test. Compile it against `android.jar`, convert it with D8, and run it on a device that has the app installed. Its argument is the path to `tests/expected-locales.json` copied onto that device. It checks real compiled resources using configuration-specific contexts without changing the global OS language.
