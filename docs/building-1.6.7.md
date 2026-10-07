# Building Tarumi 1.6.7

The signed release APK uses app tag `v1.6.7` and parser commit `79cef010b082175b5a36adb81e1c1f62047a9782`. The parser commit includes Kotatsu-Redo's updates and Tarumi's existing source patches.

Use JDK 17 and an Android SDK with platform 36. Clone both repositories into the same parent directory:

```powershell
git clone --branch v1.6.7 https://github.com/preymium5-ctrl/Tarumi.git Tarumi
git clone https://github.com/preymium5-ctrl/kotatsu-parsers-redo.git kotatsu-parsers-redo
git -C kotatsu-parsers-redo checkout 79cef010b082175b5a36adb81e1c1f62047a9782
Set-Location Tarumi
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --no-configuration-cache
node scripts/test-cloudflare-detection.cjs
```

The default sibling parser checkout is automatically included in the build. An alternative checkout location can be passed as `-PparsersLocalPath=C:/path/to/parsers`. This builds the pinned source directly without depending on JitPack publication.

To build a release, supply your own signing configuration in the gitignored `keystore.properties` file and run:

```powershell
./gradlew.bat :app:assembleRelease --no-daemon --no-configuration-cache
```

The release signing configuration expects `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Only the established Tarumi release key produces an APK that can update the official installation. Signing keys and passwords are not included in the repository.

The official `app-release.apk` certificate SHA-256 is `cb56228a6a8c434c5ba8504f6af9ed4534ff7da4b48a6d45fa059ca9a725e41e`, unchanged from v1.6.6. The published v1.6.7 APK SHA-256 is `70643a1220db514ac8f25125c7d9dd9315c24a6940dbae079e3b28e7f5130b83`.

The release passed debug and release builds, release vital lint, 20 targeted parser tests, 14 app tests and 12 Cloudflare detection checks. One existing app test was skipped. Installation and on-device reader checks were not performed because no device was connected.
