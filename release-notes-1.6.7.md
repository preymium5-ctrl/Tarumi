# Tarumi v1.6.7

Tarumi **v1.6.7** is a source engine and reader maintenance update. It brings the latest Kotatsu-Redo source changes into Tarumi, adds OniSaga, Chikari and Ryukomik, updates sites that changed their domains or backends, and improves Webtoon loading, shared links and Cloudflare verification.

> [!IMPORTANT]
> **AllManga is now Mkissa:** The source keeps its existing internal identifier but now appears as **Mkissa** in the catalog and uses the new site and API. The bundled engine contains **1374 source entries**, including entries that upstream marks as broken; this count does not mean every website is available.

**Package:** `com.tarumi.reader` · **Version:** `1.6.7` (`versionCode` **2173**)
**Artifact:** `app-release.apk` (minified, resource-shrunk, R8-obfuscated, and signed with the established Tarumi release certificate)

Users on **v1.6.6** can install this release directly as an in-place update. The signing certificate, v1/v2 signature schemes and package name are unchanged.

---

### 🌐 New sources

* Added **OniSaga** in seven variants: English, French, Japanese, Brazilian Portuguese, Portuguese, Latin American Spanish and Spanish.
* Added **Chikari** (English), with search and genre filtering. Corrected the genre labels and identifiers so filters send the API's genre slug.
* Added **Ryukomik** (Indonesian).
* Added **MangaYY** with upstream's broken flag, so it remains hidden from the normal source catalog.
* The bundled engine now ships **1374** source entries, up from **1364** in v1.6.6.

---

### 🔧 Repointed and rebuilt sources

* **AllManga / Mkissa** migrated to the new domain and API, with updated search, details, chapter metadata and reader requests.
* **Comicazen** received an upstream parser rebuild covering its catalog, details, chapters and reader pages.
* **AtsuMoe** now requests page images from the site's CDN with the expected image headers.
* **BatCave** handles the site's DLE Guard challenge and updates search, filtering and list parsing.
* **Comix** now uses the site's content rating filter and maps safe, suggestive and adult entries consistently. Empty filters continue to request safe content.
* **Mangago** groups chapters by their actual uploader, collects official releases in a branch, removes duplicate URLs and fills missing numbered chapters from other branches.
* **RavenScans** adopts upstream's updated domain, series path and pagination settings.
* **ReadComicsOnline** now supports title search through the site's autocomplete API.
* **ComX**, **ZenManga** and **CuuTruyen** received upstream parser rewrites and API handling updates.
* **NineManga** language variants now use the corresponding **Niadd** domains. Mobile Niadd links are also recognized.

---

### 📖 Reader and Webtoon loading

* Fixed Webtoon pages collapsing when the viewport has not been measured yet.
* Prevented invalid zoom calculations before image dimensions are available.
* Synchronized page loading tasks and prevented duplicate prefetch requests.
* Reserved loading capacity for visible pages and added OniSaga-specific prefetch limits.
* Refreshing the reader now clears queued prefetch work and cancels previous page tasks before restarting.
* Chapter loading errors now reach the reader instead of becoming empty page lists. Tarumi's offline-first loading and continuous chapter handling are preserved.

---

### 🔗 Shared links and source replacement

* Webtoons episode links now resolve to their parent series.
* Links on shared source domains select the appropriate language and prefer parsers that are not marked as broken.
* Link resolution also considers source domains configured in settings.
* Shared app links can fall back to the exact series URL when source search fails, without selecting a similarly named title instead.
* Automatic source replacement stays in the original source language. Users can still explicitly choose a target source in another language.
* Candidate detail requests share the search concurrency limit, reducing simultaneous requests during source replacement.

---

### 🛡️ Cloudflare verification

* Chapter and image loads now use Tarumi's existing verification coordinator.
* Background chapter loading and image prefetch can wait for an active verification session without opening a new verification dialog.
* Hidden challenge elements no longer make a completed page appear to be stuck in verification.
* Completed text-only responses, including AJAX and JSON pages, are recognized correctly.

---

### ⚙️ Stability and polish

* Tracker cancellation now propagates through library synchronization.
* Preserved Tarumi's animated-image request support and existing interface.
* Kept the Eris Scans, MangaPlex, ManhwaRead, ManhuaRMTL, OmegaScans, Hitomi, Asura and DivaScans source patches intact.
* Verified debug and release builds, release vital lint, **20 parser tests**, **14 app tests** and **12 Cloudflare regression cases**. One previously ignored app test remains skipped.

---

### 📦 Build and installation

* Bumped Tarumi to **`1.6.7`** (`versionCode` **2173**).
* Ships as **`app-release.apk`**, signed with the same Tarumi release certificate used by **v1.6.6** for in-place updates.
* Compared against the exact published v1.6.6 APK: both pass signature verification and have identical certificate SHA-256 fingerprints.
* Release builds remain minified, resource-shrunk and R8-obfuscated.
* Parser revision: [`79cef010b0`](https://github.com/preymium5-ctrl/kotatsu-parsers-redo/commit/79cef010b082175b5a36adb81e1c1f62047a9782), based on [Kotatsu-Redo's source revision `b3c0000245`](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/commit/b3c000024563920d3eecde5816f2e9d3734fa467) with Tarumi's patches. App fixes were adapted from [Kotatsu-Redo 9.8.4](https://github.com/Kotatsu-Redo/Kotatsu-Redo/releases/tag/9.8.4).

---

### 💡 Tips

* Reopen affected titles after updating so their source details and chapter lists refresh.
* Look for **Mkissa** in the source catalog if you previously used AllManga.
* Select your preferred OniSaga language variant when enabling the source.
* MangaYY remains marked as broken and is not available in the normal catalog.
* Existing Eris Scans chapters marked **🔒** still require unlocking on the website.
* Community and support: https://discord.gg/hVr5KNQRnk
