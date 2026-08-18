# Tarumi v1.6.1 Changelog

Tarumi **v1.6.1** is a reliability and UX fix release on top of 1.6.0. It focuses on **Recent Updates controls**, **download chapter progress**, **chapter list positioning**, **offline/NSFW reading progress**, **next-chapter loading for downloads**, and **Comix chapter lists**.

Install-time updates remain seamless for existing debug builds (same Android Debug signing as **v1.5.5** / **v1.6.0**).

---

### 🏠 Recent Updates Toggle
* **Switch always stays reachable**: turning Recent Updates off no longer hides the on/off control with the section body.
* Only the **summary / list / pagination** hide when off; the title + switch remain so you can turn it back on.
* Extra spacing so the control does **not sit under the bottom navbar**.
* Also available under **Settings → Appearance → Performance → Recent Updates on Home**.

---

### ⬇️ Downloads — Per-Chapter Progress
* Expanded download items show a **progress bar per chapter** (not only a checkmark).
* Works for **online**, **offline**, and **NSFW** download rows.
* Live progress is taken from WorkManager data (`current chapter` / pages) and updates while the job runs.
* Chapter lists for downloads prefer **local/DB chapters first**, so offline/NSFW jobs still expand with chapter rows.

---

### 📖 Chapter List UX
* Opening a comic’s **chapter list always starts at the top** of the scroll view.
* No more jumping mid-list / “current chapter” middle position when opening from **bookmarks**.

---

### 📴 Offline & NSFW Read Progress (incl. HiveComic)
* Fixed a real bug reported for **HiveComic NSFW** downloads read offline:
  * **Progress bar did not move**
  * **Could not advance to the next chapter**
* Reader now **prefers local/downloaded pages first** when loading a chapter (current + next/prev), even if the chapter still carries a remote source id.
* History is saved reliably when the reader is **not** in incognito (including NSFW offline sessions after you decline NSFW-incognito).
* Details recompute **chapter-local** progress from page index + local files when chapters/history change.
* Chapter list matching for downloads uses **id or URL** so bars stay attached after offline remaps.

**Note:** If **Settings → Sources → Incognito for NSFW** is set to **Enable**, progress is intentionally not saved. Use **Ask** or **Disable** to keep progress on NSFW titles. Next chapter offline only works for chapters you actually **downloaded**.

---

### 🧩 Comix — Chapter Lists
* Comix titles that showed **no chapter list** are fixed.
* Capture hooks install **before** the site’s signed chapter XHR (too-late injection was dropping results).
* Pure single-team first pages are **kept** (previously dropped → empty list).
* Fallbacks: `initial-data` → bootstrapped WebView HTML → evaluateJs poll → DOM chapter links → legacy intercept.

---

### 🔧 Technical / Internals
* WebView request interceptor injects page scripts on **page start** (and retries after Cloudflare clears).
* `ChaptersLoader` local-first page resolution via `LocalMangaRepository`.
* `ProgressUpdateUseCase` offline page counting improvements.
* Download WorkData carries per-chapter progress fields for the UI overlay.

---

### 📦 Build
* Bumped Tarumi to version **`1.6.1`** (`versionCode` **2167**).
* Ships as **`app-debug.apk`**, signed with the same **Android Debug** certificate as prior GitHub debug releases (including **v1.5.5** / **v1.6.0**), so users on those builds can **update in place**.

The attached `app-debug.apk` is the debug build produced from this release tag.
