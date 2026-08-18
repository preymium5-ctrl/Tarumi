package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

@Keep
enum class NavItem(
	@IdRes val id: Int,
	@StringRes val title: Int,
	@DrawableRes val icon: Int,
) {

	HOME(R.id.nav_home, R.string.home, R.drawable.ic_home),
	HISTORY(R.id.nav_history, R.string.history, R.drawable.ic_history_selector),
	FAVORITES(R.id.nav_favorites, R.string.library, R.drawable.ic_bookmark_selector),
	LOCAL(R.id.nav_local, R.string.on_device, R.drawable.ic_storage_selector),
	EXPLORE(R.id.nav_explore, R.string.discover, R.drawable.ic_explore_selector),
	SUGGESTIONS(R.id.nav_suggestions, R.string.suggestions, R.drawable.ic_suggestion_selector),
	FEED(R.id.nav_feed, R.string.feed, R.drawable.ic_feed_selector),
	UPDATED(R.id.nav_updated, R.string.updated, R.drawable.ic_updated_selector),
	BOOKMARKS(R.id.nav_bookmarks, R.string.bookmarks, R.drawable.ic_bookmark_selector),
	STATS(R.id.nav_stats, R.string.stats, R.drawable.ic_timer),
	SETTINGS(R.id.nav_settings, R.string.settings, R.drawable.ic_settings),
	;

	fun isAvailable(settings: AppSettings): Boolean = when (this) {
		STATS -> false
		SUGGESTIONS -> settings.isSuggestionsEnabled
		UPDATED, FEED -> settings.isTrackerEnabled
		else -> true
	}
}
