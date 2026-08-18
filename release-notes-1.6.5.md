# Tarumi v1.6.5

Tarumi **v1.6.5** is a focused reader reliability and navigation refinement update. It improves comic reading on older Android devices, makes bookmark repair more precise, restores the familiar native navigation bar, and corrects mismatched ManhwaRead chapters.

> [!IMPORTANT]
> **Android 8 and ManhwaRead fixes:** The reader now uses a lower-memory compatibility path on Android 8, while ManhwaRead chapter lists are restricted to the selected comic so recommendations can no longer appear as unrelated chapters.

**Package:** `com.tarumi.reader` · **Version:** `1.6.5` (`versionCode` **2171**)
**Artifact:** `app-release.apk` (minified, resource-shrunk, R8-obfuscated, and signed with the established Tarumi release certificate)

Users on **v1.6.4** can install this release directly as an in-place update. Users of an old debug build (`app-debug.apk` / `com.tarumi.reader.debug`) should back up their data and uninstall the debug package before installing this release.

---

### 📖 Android 8 reader reliability

* Added a compatibility path for Android 8 that avoids unsupported foldable-window tracking behavior in the reader.
* Reduced reader pager retention and disabled eager page prefetch on Android 8 to lower memory pressure when opening chapters.
* Added defensive error handling around window-layout tracking so unsupported device behavior cannot close the reader.

---

### 🔧 More precise bookmark repair

* Added a source-selection dialog to the bookmark **Fix** action.
* Users can choose one or more sources, select all sources, and limit automatic comic repair to only the providers they trust.
* Propagated the selected source list through the repair service and matching pipeline for consistent results.

---

### 🌐 ManhwaRead chapter matching

* Fixed ManhwaRead series pages mixing chapters from recommended comics into the selected title.
* Chapter links are now accepted only when their parent manga slug exactly matches the comic being viewed.
* Added regression coverage for unrelated recommendations and similar-looking title slugs.

---

### 🧭 Familiar and stable navigation

* Restored the native bottom navigation design already used across Tarumi instead of the animated floating navigation bar.
* Removed **History** from the main navigation bar and automatically filters it from previously saved navigation layouts.
* Kept navigation icons at a stable size when moving between sections, including Settings.
* Removed the obsolete floating-navigation appearance toggle and normalized labels, spacing, pinning, and action-button behavior.

---

### ⚙️ Stability and polish

* Preserved existing app data during upgrades by continuing to use Tarumi's established release signing certificate.
* Updated the bundled parser revision to include the corrected ManhwaRead mapping logic.
* Added targeted parser regression testing and verified debug and release builds against the updated source bundle.

---

### 📦 Build and installation

* Bumped Tarumi to **`1.6.5`** (`versionCode` **2171**).
* Ships as **`app-release.apk`**, signed with the same Tarumi release certificate used by **v1.6.4** for seamless in-place updates.
* Release builds remain minified, resource-shrunk, and R8-obfuscated.

---

### 💡 Tips

* On Android 8, close and reopen any chapter that previously exited immediately so it starts with the new compatibility path.
* When using bookmark **Fix**, select only the sources you want Tarumi to search for replacements.
* Reopen a ManhwaRead title after updating to refresh its corrected chapter list.
* Prefer the official GitHub `app-release.apk` over older debug packages.
* Community and support: https://discord.gg/hVr5KNQRnk
