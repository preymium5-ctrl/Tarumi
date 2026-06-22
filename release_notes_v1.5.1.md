# Tarumi v1.5.1 Release Notes

This release introduces a new comprehensive low-memory **Performance Mode** to optimize RAM consumption, along with critical parser fixes for Comix and MangaDistrict.

---

## 🚀 Performance Mode Settings & UI
- **Preference Configurations**: Added the new `KEY_PERFORMANCE_MODE` preference with full localization support.
- **Dedicated Section**: Introduced a new **Performance** category in the settings screen, housing a quick toggle to activate **Performance Mode**.
- **Instant Application**: Updated the preferences listeners to trigger a safe hot-recreation of the application stack upon toggle, applying all RAM and network optimizations immediately without requiring a manual restart.

---

## ⚡ Global RAM Optimizations
When **Performance Mode** is active, the system configures `Context.isLowRamDevice()` to return `true` globally, which dynamically triggers the following constraints:
- **RGB_565 Image Deserialization**: Forces Coil to decode all images using the `RGB_565` configuration instead of standard `ARGB_8888`, reducing memory usage by 50% across the entire application image rendering pipeline.
- **Reader Optimizations**: Disables offscreen eager page pre-loading and increases offscreen page downsampling within the reader to minimize heap overhead.
- **Aggressive Cache Eviction**: Caps details cache and reader page caches to a maximum size of `1`.

---

## 🏠 Homepage Performance Modifications
To drastically decrease startup latency and background overhead under **Performance Mode**:
- **Frozen Pools**: Recommendations, trending, and featured pool cache policies are locked to a static period (`0L`), preventing unnecessary network checks or random shuffles when cache is present.
- **Lazy Metadata Loading**: Disabled startup details crawling for trending comics.
- **Simplified UI Feed**: Hides the **Recent Updates** feed section from the homepage dashboard, and disables the background network worker crawls scheduled to pull new updates.

---

## 🛠️ Comix Parser Bug Fixes
- **Turnstile False-Positive Bypass**: Refined the `challengeDetected()` script logic inside the parser to identify only *active* Cloudflare Turnstile verification challenge pages. This prevents normal background telemetry scripts from triggering false-positive blocks.
- **Graceful WebView API Fallback**: Reworked `loadInitialQueries` to quietly return `null` on OkHttp network or Cloudflare HTTP 403 blocks. This safely triggers the parser's natural signed WebView bridge (`webViewApiJson()`) fallback rather than throwing a prompt error immediately.

---

## 🛠️ MangaDistrict Parser Fixes
- **Bypass Broken AJAX Endpoint**: The source website `mangadistrict.com` recently made changes that caused its admin AJAX search/listing endpoint to return empty results. We disabled AJAX listing (`withoutAjax = true`) for this source, forcing it to fall back to parsing standard HTTP GET search and list pages directly. This fully restores the browsing and search functionality inside the application.
