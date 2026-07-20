# ⚠️ IMPORTANT — READ THIS FIRST

## APK type has changed — please reinstall from the new build

**This is not a normal in-place update.**

Starting with **v1.6.2**, Tarumi ships as a proper **release** APK (`app-release.apk`):

* **New package id:** `com.tarumi.reader` (previous GitHub builds used the **debug** package `com.tarumi.reader.debug`)
* **New release signing certificate** (no longer the shared Android debug keystore)
* Android will treat this as a **different app** — you **cannot** update the old debug APK over itself

### What you should do

1. **Join Discord for help and the latest download link:**  
   👉 **https://discord.gg/hVr5KNQRnk**
2. **Before uninstalling, back up your data** (so reading progress, bookmarks, and library survive the switch):  
   **Settings → Backup and restore → Create data backup**  
   Save the backup file somewhere safe (Files / Drive / PC) — you will need it after installing the new APK.
3. **Uninstall** the old Tarumi / `app-debug` build on your device
4. Download **`app-release.apk`** from this GitHub release (or from Discord if mirrors are posted there)
5. Install the new release APK
6. **Restore your progress:** open Tarumi → **Settings → Backup and restore → Restore** (pick the backup you saved in step 2)

If something looks wrong after install, **open a ticket or ask in Discord first** — we can walk you through the switch. Do not stay on old `app-debug.apk` builds; they will no longer receive the same release channel updates.

---

# Tarumi v1.6.2 Changelog

Tarumi **v1.6.2** is a **release-channel** build with **R8 minify + resource shrink + code obfuscation**, plus reader/parser fixes for animated AVIF, Hitomi GameCG, webtoon chapter flow, CGI typing, and splash polish.

---

### 🔒 Release build & protection
* Ships as **`app-release.apk`** (not `app-debug.apk`)
* Package: **`com.tarumi.reader`** · version **`1.6.2`** (`versionCode` **2168**)
* **Minify + shrink resources** enabled for release
* **R8 obfuscation** enabled (removed `-dontobfuscate`); keep rules for Hilt, Room, parsers, Coil, WorkManager, MediaPipe, ACRA, etc.
* Signed with a dedicated **Tarumi release keystore** (not the debug certificate)

---

### 🖼️ Animated AVIF / motion pages
* Added **Animated AVIF** decoding path so moving/CGI-style pages play instead of freezing as a still
* Image extension helpers recognize animated AVIF (`avis` and related markers)
* Coil decoder wiring updated so animated AVIF is preferred when the file is animated

---

### 🎮 Hitomi — GameCG & animated galleries
* **GameCG** Hitomi titles (e.g. galleries that were missing before) now map into the app as readable entries
* Animated page files from Hitomi are handled so motion content can play in the reader

---

### 📖 Reader & webtoon loading
* **Webtoon / continuous reader:** next-chapter auto-load no longer gets stuck after finishing a chapter
* **ChaptersLoader / PageLoader:** stronger **local-first** page resolution for downloaded chapters (current + adjacent)
* **BasePageHolder:** safer handling for animated / special image types when binding pages
* **ReaderViewModel:** more reliable chapter boundary / next-chapter flow after the recent offline progress work

---

### 🏷️ CGI / motion comic typing
* Home **ComicTypeClassifier** recognizes **CGI** / motion-style titles
* Details screen uses a dedicated **CGI** type color chip so these titles stand out from plain manga/manhwa labels

---

### ✨ Splash screen polish
* Status bar / system bars immersion improved on splash
* Splash video + first-frame assets cropped so the **status bar is not baked into** the video artwork
* Theme flags for Android 12+ splash surface cleaned up

---

### 🏠 Home recommendations (Manga Plus EN)
* Fixed **empty Manhua / Manga / Smart recommendation rails**
* Manga Plus is a single-page source (`offset > 0` always returned empty). Home now always loads the full EN ranking, then slices different cards per rail in memory.

### 🔧 Other
* Minor Ask AI ViewModel / AppModule wiring touch-ups for the new image decoders
* Refresh / chapter-edge messaging cleaned up so false “content removed” style labels are less likely after normal chapter transitions

---

### 📦 Build
* Bumped Tarumi to **`1.6.2`** (`versionCode` **2168**)
* Artifact: **`app-release.apk`** (minified, obfuscated, release-signed)

**Again:** uninstall the old debug build, join **https://discord.gg/hVr5KNQRnk**, then install this release APK.
