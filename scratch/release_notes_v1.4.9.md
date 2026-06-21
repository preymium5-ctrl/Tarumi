# Tarumi v1.4.9

Welcome to Tarumi v1.4.9! This update introduces a major redesign of the source catalog management system, restores the beloved compact reader navigation, and adds visual page preview support.

## Key Changes & Enhancements

### 1. Restored Compact Reader Sheet & Page Previews
* **Beloved Design Restored**: Reverted the progress capsule bottom sheet in the reading UI back to the clean, compact vertical list format of `ReaderChaptersSheet`.
* **Visual Page Navigation**: Added tab buttons in the sheet header allowing you to seamlessly toggle between the **Chapters** list and a **Pages** grid of page thumbnail previews.
* **Streamlined UI**: Kept the layout focused on reading navigation by omitting the bookmarks tab entirely from this bottom sheet.

### 2. Segmented Source Catalog Interface
* **Added / Available Tabs**: Replaced the previous design with a native Material segmented toggle control (`MaterialButtonToggleGroup`). Easily switch between **Added** (your enabled sources) and **Available** (remaining catalogs you can search and add).
* **Clarity on Disabling**: Replaced the trashcan icon with a red cross close/remove (**X**) icon to make it clear that clicking it disables/removes the catalog from your active source lists.

### 3. All-Catalog Source Discovery & NSFW Settings
* **All-Catalog Support**: Restored the ability to view, search, and manage all catalog genres (Manga, Comics, Hentai, etc.) directly in the Source Catalog.
* **Settings-Driven Filtering**: Integrated global settings checks so that NSFW sources filter dynamically based on your preference. When NSFW content is enabled, active NSFW sources are correctly displayed in the Explore available sources list.
