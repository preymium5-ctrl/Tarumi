# Tarumi v1.6.6

Tarumi **v1.6.6** is a source engine update. It syncs the bundled parsers with the latest Kotatsu-Redo revision, brings a new Indonesian source, repairs several sites that changed their backends, and fixes Eris Scans series that were showing only a fraction of their chapters.

> [!IMPORTANT]
> **Eris Scans chapter lists:** Paid early access chapters were silently dropped while the shortcut button above the chapter list smuggled the newest one back in, so titles ended up with holes in the middle of their numbering. Eris Scans now lists every chapter the website has, with locked chapters marked **🔒**.

**Package:** `com.tarumi.reader` · **Version:** `1.6.6` (`versionCode` **2172**)
**Artifact:** `app-release.apk` (minified, resource-shrunk, R8-obfuscated, and signed with the established Tarumi release certificate)

Users on **v1.6.5** can install this release directly as an in-place update. Users of an old debug build (`app-debug.apk` / `com.tarumi.reader.debug`) should back up their data and uninstall the debug package before installing this release.

---

### 📖 Eris Scans chapter lists

* Fixed series listing only a part of their chapters. A 39 chapter title such as *Samo* previously showed 6 chapters, and shorter series were missing chapters in the middle of the numbering.
* Chapters are now read from the site's chapter grid only, so the "first chapter" and "latest chapter" shortcuts can no longer be mistaken for chapter entries and no longer overwrite release dates.
* Paid early access chapters are kept in the list and marked with a **🔒** prefix instead of being hidden, so numbering always matches the website.
* Opening a locked chapter now reports that it has to be unlocked with coins on the website instead of failing with an empty page list.
* Added regression coverage that verifies a full series is listed with complete numbering and release dates.

---

### 🌐 New and removed sources

* Added **Voratoon** (Indonesian), a JSON API source with search, genre, format and status filters.
* Removed **KomikCast** (Indonesian), which was dropped upstream. Saved comics from it will no longer resolve.
* **RimuScans** (French) was rewritten for its new Next.js site and **HentaiOrigines** (French) was repaired for the Origines redesign, but both remain marked as broken upstream, so they stay hidden in the source catalog for now.
* The bundled engine now ships **1364** sources.

---

### 🔧 Repointed and rebuilt sources

* **SoulScans** (Indonesian) rebuilt on the site's new API host, with API driven search, author, genre, status and type filters.
* **Geass Comics** (Portuguese) re-enabled and repointed at its new backend, with inline chapter lists and faster details loading.
* **MgKomik** (Indonesian) re-enabled with standard request headers instead of the old randomized header set.
* **Lunar Manga** migrated to its new domain, API and image CDN, with reader image decryption reimplemented for the site's new scheme and duplicate chapters filtered out.
* **Desu** (Russian) switched from HTML scraping to the site's JSON API, which restores real upload dates and adds alternate titles, rating, status and authors. Its mirror list is now a single domain.
* **Mangadotnet** moved to the site's search API with multi tag and tag exclusion, author, year range, status, content rating, content type and demographic filters.
* **MangasOrigines.fr** repaired after the site redesign, covering details, genres, status, the AJAX chapter list and reader pages.
* **Comix** now loads chapter lists straight from the site's own signed API in parallel with details, handles the older image protection scheme, and retries alternate image paths.
* **MangaFire** exposes its user agent option on the source settings screen.

---

### 🛡️ Cloudflare verification

* Added a Cloudflare verification request path in the parser engine, used first by **Comix**: challenge pages are detected from the page itself, a failed load is retried once before the browser is opened, and hosts without a dedicated resolver keep the previous browser prompt behavior.

---

### ⚙️ Stability and polish

* Preserved existing app data during upgrades by continuing to use Tarumi's established release signing certificate.
* Updated the bundled parser revision on top of Tarumi's own source patches, keeping the local Eris Scans, MangaPlex, ManhwaRead, ManhuaRMTL, OmegaScans, Hitomi, Asura and DivaScans work intact.
* Verified the debug and release builds and ran targeted parser regression tests against the updated source bundle.

---

### 📦 Build and installation

* Bumped Tarumi to **`1.6.6`** (`versionCode` **2172**).
* Ships as **`app-release.apk`**, signed with the same Tarumi release certificate used by **v1.6.5** for seamless in-place updates.
* Release builds remain minified, resource-shrunk, and R8-obfuscated.

---

### 💡 Tips

* Reopen your Eris Scans titles after updating so their chapter lists are rebuilt with the missing chapters.
* Chapters marked **🔒** are paid early access on Eris Scans and have to be unlocked on the website before they can be read.
* If you were following comics on KomikCast, look for them on another source since that site has been removed.
* Reopen Lunar Manga, Desu, SoulScans and Geass Comics titles once so they pick up their new hosts.
* Prefer the official GitHub `app-release.apk` over older debug packages.
* Community and support: https://discord.gg/hVr5KNQRnk
