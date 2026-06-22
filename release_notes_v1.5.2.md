# Tarumi v1.5.2 Release Notes

This release contains a critical fix for the **MangaDistrict** source.

---

## 🛠️ MangaDistrict Parser Fixes
- **Bypass Broken AJAX Endpoint**: The source website `mangadistrict.com` recently made changes that caused its admin AJAX search/listing endpoint to return empty results. We disabled AJAX listing (`withoutAjax = true`) for this source, forcing it to fall back to parsing standard HTTP GET search and list pages directly. This fully restores the browsing and search functionality inside the application.
