# Tarumi v1.6.3

Tarumi **v1.6.3** is a stability and polish update for the release channel. It improves **Omega Scans / HeanCMS reading**, **home recommendations**, **chapter list navigation**, **notifications layout**, **Continue Reading controls**, and the **splash animation**.

**Package:** `com.tarumi.reader` · **Version:** `1.6.3` (`versionCode` **2169**)  
**Artifact:** `app-release.apk` (minified, obfuscated, signed with the same Tarumi release certificate as **v1.6.2**)

Users already on **v1.6.2** can update in place. If you are still on an old **debug** build (`app-debug.apk` / `com.tarumi.reader.debug`), uninstall that package first, create a data backup, then install this release.

---

### 📖 Omega Scans & HeanCMS — pages loading
* Fixed a common failure where chapter images showed **“Content not found or removed”** even though pages were available on the website.
* Especially affected **older completed** titles (for example *A Wonderful New World*, *Panty Note*, *Intern Haenyeo*) after early chapters.
* **Cause:** free chapters on Next.js HeanCMS sites no longer expose classic `<img>` tags; images ship as preload links and embedded media URLs.
* **Fix:** the parser now reads preload links and series upload URLs as fallbacks, so pages load correctly in Tarumi.

---

### 🏠 Home recommendations (Manga Plus EN)
* Fixed **empty Manhua / Manga / Smart recommendation rails**.
* Manga Plus is a single-page source (`offset > 0` always returned empty). Home now loads the full English ranking and slices different cards per rail in memory.

---

### 🔔 Notifications & chapter list
* Opening the chapter list from **notifications** (and related flows) now starts at the **top of the list**, not the middle or bottom.
* **Notifications** screen layout improved for real devices:
  * Header respects the status bar / cutouts
  * Cards and action chips fit more cleanly without overflow
  * Title no longer collides with the **NEW** badge
  * Action buttons are tappable and balanced

---

### ⚙️ Continue Reading visibility
* New setting: **Settings → Appearance → Performance → Continue Reading on Home**
* Turn it off to hide the Continue Reading section on Home (reading history is still saved).

---

### ✨ Splash animation
* Removed the **Android navigation button bar** that was baked into the intro video.
* Video is cropped and **center-cropped** on screen so the animation sits centered on the device.
* First-frame placeholder updated to match the cropped clip.

---

### 📦 Build
* Bumped Tarumi to **`1.6.3`** (`versionCode` **2169**).
* Ships as **`app-release.apk`**, signed with the **same release keystore** as **v1.6.2**.
* Release build remains **minified, resource-shrunk, and R8-obfuscated**.

---

### 💡 Tips
* If an Omega Scans chapter still fails once, pull to refresh or re-open the chapter so the new page extractor runs.
* Prefer this GitHub **`app-release.apk`** (or the official Discord link) over old debug APKs.
* Community & support: https://discord.gg/hVr5KNQRnk
