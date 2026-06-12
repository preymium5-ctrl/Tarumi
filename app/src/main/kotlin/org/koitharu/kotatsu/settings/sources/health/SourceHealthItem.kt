package org.koitharu.kotatsu.settings.sources.health

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.SourceDiagnostics
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaParserSource

data class SourceHealthItem(
	val source: MangaParserSource,
	val status: SourceHealthStatus,
	val isEnabled: Boolean,
	val isPinned: Boolean,
	val diagnostics: SourceDiagnostics?,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SourceHealthItem && other.source == source
	}
}

enum class SourceHealthStatus(
	@StringRes val titleResId: Int,
	@ColorRes val colorResId: Int,
) {
	HEALTHY(
		titleResId = R.string.source_health_working,
		colorResId = R.color.common_green,
	),
	SLOW(
		titleResId = R.string.source_health_slow,
		colorResId = R.color.warning,
	),
	AVAILABLE(
		titleResId = R.string.source_health_available,
		colorResId = R.color.blue_primary,
	),
	CAPTCHA(
		titleResId = R.string.source_health_captcha,
		colorResId = R.color.warning,
	),
	BLOCKED(
		titleResId = R.string.source_health_blocked,
		colorResId = R.color.common_red,
	),
	BROKEN(
		titleResId = R.string.source_health_broken,
		colorResId = R.color.common_red,
	),
	MISSING_DETAILS(
		titleResId = R.string.source_health_missing_details,
		colorResId = R.color.warning,
	),
	MISSING_CHAPTERS(
		titleResId = R.string.source_health_missing_chapters,
		colorResId = R.color.common_red,
	),
}
