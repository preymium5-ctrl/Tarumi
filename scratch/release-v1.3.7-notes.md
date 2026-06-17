## Tarumi 1.3.7

This update improves manga details, related manga placement, metadata cleanup, source diagnostics, recent-update reliability, and update notification behavior.

### Manga Details
- Moved Related manga above the chapter card/list so recommendations appear before the chapter section.
- Restored the visible Related manga card on portrait and landscape manga detail layouts.
- Reduced Related manga delay by preventing the related fetch from restarting when the details page hydrates from seed data to full source details.
- Related manga now starts eagerly for the details page instead of waiting lazily for a later subscription.
- Fixed Asura Comics creator metadata cleanup so site warning text such as Safari/ad-blocker notices no longer appears as the artist name.
- Hardened live source-page creator fallback so junk website notices cannot replace author or artist values.
- Improved details metadata normalization for ratings, creators, status, type tags, and noisy descriptions.

### Source Diagnostics
- Added Source Health System Checker in Settings and Sources settings.
- Added health states for English sources: working, slow, Cloudflare/captcha, missing details, missing chapters, and broken.
- Added source health rows with source icon, status, last checked time, recent item count, failure count, and failure streak.
- Added Metadata Quality Dashboard to show which English sources are missing rating, author, artist, status, type, description, chapters, or chapter dates.
- Added internal metadata confidence labels for source parser, source-page fallback, inferred metadata, smart match, and unknown values.

### Recent Updates
- Added per-source recent-update diagnostics with success/failure logging.
- Added last checked and item count tracking for recent-update crawls.
- Added cooldown handling so sources with repeated failures are skipped temporarily instead of slowing the app repeatedly.
- Kept the recent-update crawler less aggressive while still supporting expanded source crawling.

### Metadata Matching
- Added conservative smart metadata matching from cached exact-title matches across sources.
- Smart matching only fills missing rating, author, status, or type when an exact normalized title/alternate-title match already exists locally.
- Avoided fuzzy matching so unrelated titles do not borrow incorrect metadata.

### Notifications and Bookmarks
- Improved new chapter notifications with chapter title, source, detected time, and direct opening to the detected chapter when available.
- Added Bookmark sorting by status group.
- Kept new-chapter ranking behavior so updated bookmarked comics can surface first.

### Build
- Version name: 1.3.7
- Version code: 2143
- APK verified with v1, v2, v3, and v3.1 signatures.
- Public asset: Tarumi-1.3.7-debug.apk
