# Tarumi v1.6.4

Tarumi **v1.6.4** is a substantial experience and reliability update. It introduces a more expressive visual foundation, stronger Cloudflare challenge recovery, a cleaner navigation experience, and a broad refresh of the manga source engine.

> [!IMPORTANT]
> **OmegaScans reader fix:** Every chapter now checks OmegaScans' published media route and API storage, then automatically uses the first origin serving a valid image. This covers titles stored differently, including *Intern Haenyeo* and *A Wonderful New World*.

**Package:** `com.tarumi.reader` · **Version:** `1.6.4` (`versionCode` **2170**)
**Artifact:** `app-release.apk` (minified, resource-shrunk, R8-obfuscated, and signed with the established Tarumi release certificate)

Users on **v1.6.3** can install this release directly as an in-place update. Users of an old debug build (`app-debug.apk` / `com.tarumi.reader.debug`) should back up their data and uninstall the debug package before installing this release.

---

### ✨ A more expressive Tarumi

* Introduced the next stage of Tarumi's expressive interface, with refreshed surfaces, typography, spacing, shapes, and motion.
* Added a modern floating navigation style with improved adaptive behavior across phones, tablets, landscape layouts, and edge-to-edge screens.
* Refined manga cards, reading progress indicators, grid sizing, app bars, and settings presentation for a cleaner and more consistent experience.
* Expanded appearance controls so navigation styling can better follow the selected Tarumi theme.
* Removed the detached Continue Reading control that could appear as a narrow extra pill on the right side of the floating navigation bar.

---

### 🛡️ Stronger Cloudflare challenge handling

* Reworked challenge detection and recovery for sources protected by Cloudflare.
* Improved browser-to-app handoff, cookie persistence, user-agent continuity, and automatic retry behavior after a challenge is completed.
* Added safer coordination between visible and background challenge flows to reduce loops, duplicate prompts, and stalled source requests.
* Improved handling of modern challenge pages that previously looked like ordinary loading or error screens.

---

### 📖 OmegaScans and reader reliability

* Fixed **OmegaScans** HTTP 404 errors across its catalogue with per-chapter origin detection instead of forcing every title through one storage host.
* Reinforced HeanCMS chapter image discovery for sources that publish pages through preload metadata or embedded application data.
* Improved network headers and source request consistency for image loading and protected websites.

---

### 🌐 Source engine refresh

* Added **Eris Scans**, including catalogue browsing, search, genres, series details, free chapters, and reader pages.
* Updated or repaired **Comix**, **Kagane**, **RavenScans**, **CosmicScans**, **MangaFire**, **PhiliaScans**, **MangaPlus**, **Desu**, and **MaidScan**.
* Added the **Canva** classification to supported Webtoons titles.
* Expanded the available catalogue with additional source support including **Cubari**, **DivaScans**, **MangaPlex**, **ManhuaRMTL**, **ManhwaRead**, **YuriBase**, and **Hwago**.
* Refreshed several regional sources, domains, filters, tags, chapter extraction paths, and image fallbacks to match their current websites.

---

### 🧭 Cleaner navigation

* Removed **Ask AI** and its associated screens, settings, local model dependency, and background service components.
* Restored **History** as the default navigation destination in its place.
* Existing navigation preferences migrate automatically, keeping upgrades clean and predictable.

---

### ⚙️ Stability and polish

* Improved new-chapter checks and tracking synchronization behavior.
* Refined responsive layouts and edge-to-edge insets throughout the app.
* Reduced unnecessary app components and removed the large local AI runtime dependency.
* Updated parser integration to the latest Tarumi-tested source bundle.

---

### 📦 Build and installation

* Bumped Tarumi to **`1.6.4`** (`versionCode` **2170**).
* Ships as **`app-release.apk`**, signed with the same Tarumi release certificate used by **v1.6.3** for seamless in-place updates.
* Release builds remain minified, resource-shrunk, and R8-obfuscated.

---

### 💡 Tips

* If a protected source opens a Cloudflare page, complete the challenge once and return to Tarumi; the app will preserve the verified session and retry the request.
* If a previously failed OmegaScans chapter is already open, close and reopen it so the corrected image route is requested.
* Prefer the official GitHub `app-release.apk` over older debug packages.
* Community and support: https://discord.gg/hVr5KNQRnk
