# Tarumi v1.3.9

This release brings layout adjustments to the Reader UI to ensure a seamless navigation experience and fixes the MangaDistrict catalog source.

## Changelog

### Reader Interface
* **Floating Up-Arrow Layout Fix**: Resolved a layout overlap where the floating "scroll-to-top" arrow button collided with the settings option on the custom reader controls when the "Show scroll advance" progress bar was enabled. The up-arrow container now dynamically adjusts its vertical offset to sit cleanly above the settings.
* **Instant Dynamic Redraw**: Enabled immediate refresh of margins and paddings when the scroll advance visibility is toggled.

### Sources & Parsers
* **MangaDistrict Source Repair**: Fixed catalog browsing for MangaDistrict (`mangadistrict.com`). Catalog list calls are transitioned from empty AJAX requests to direct, clean URL routing under a user-maintained fork.

---

## Assets
Install the attached `Tarumi-1.3.9-debug.apk` directly on your Android device. It is signed with the same key for continuous updates.
