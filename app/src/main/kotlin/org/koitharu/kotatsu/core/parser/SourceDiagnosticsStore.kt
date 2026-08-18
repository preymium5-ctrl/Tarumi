package org.koitharu.kotatsu.core.parser

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SourceDiagnosticsStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	fun observeReports(): Flow<Map<String, SourceDiagnostics>> = callbackFlow {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == KEY_REPORTS) {
				trySendBlocking(getReports())
			}
		}
		preferences.registerOnSharedPreferenceChangeListener(listener)
		awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
	}.onStart { emit(getReports()) }

	fun getReports(): Map<String, SourceDiagnostics> {
		val root = preferences.getString(KEY_REPORTS, null)?.let(::JSONObject) ?: return emptyMap()
		return buildMap(root.length()) {
			for (key in root.keys()) {
				put(key, SourceDiagnostics.fromJson(root.getJSONObject(key)))
			}
		}
	}

	fun recordDetails(manga: Manga, origin: MetadataOrigin, elapsedMs: Long? = null) {
		update(manga.source.name) { current ->
			current.recordDetails(manga, origin, elapsedMs)
		}
	}

	fun recordRecentCheck(source: MangaParserSource, success: Boolean, itemsFound: Int, elapsedMs: Long, error: Throwable?) {
		update(source.name) { current ->
			current.recordRecentCheck(
				now = System.currentTimeMillis(),
				success = success,
				itemsFound = itemsFound,
				elapsedMs = elapsedMs,
				error = error?.javaClass?.simpleName ?: error?.message,
			)
		}
	}

	fun recordSourcePageFallback(manga: Manga, hasRating: Boolean, hasAuthor: Boolean, hasArtist: Boolean) {
		update(manga.source.name) { current ->
			current.copy(
				ratingOrigin = if (hasRating) MetadataOrigin.SOURCE_PAGE_FALLBACK else current.ratingOrigin,
				authorOrigin = if (hasAuthor || hasArtist) MetadataOrigin.SOURCE_PAGE_FALLBACK else current.authorOrigin,
			)
		}
	}

	fun recordSmartMatch(
		manga: Manga,
		hasRating: Boolean,
		hasAuthor: Boolean,
		hasType: Boolean,
	) {
		update(manga.source.name) { current ->
			current.copy(
				ratingOrigin = if (hasRating) MetadataOrigin.SMART_MATCH else current.ratingOrigin,
				authorOrigin = if (hasAuthor) MetadataOrigin.SMART_MATCH else current.authorOrigin,
				typeOrigin = if (hasType) MetadataOrigin.SMART_MATCH else current.typeOrigin,
			)
		}
	}

	fun shouldSkipRecentCrawl(source: MangaParserSource, now: Long = System.currentTimeMillis()): Boolean {
		val report = getReports()[source.name] ?: return false
		return report.consecutiveFailures >= FAILURE_COOLDOWN_THRESHOLD &&
			now - report.lastFailureAt < FAILURE_COOLDOWN_MS
	}

	private fun update(source: String, block: (SourceDiagnostics) -> SourceDiagnostics) {
		val root = preferences.getString(KEY_REPORTS, null)?.let(::JSONObject) ?: JSONObject()
		val current = root.optJSONObject(source)?.let(SourceDiagnostics::fromJson) ?: SourceDiagnostics(source = source)
		root.put(source, block(current).toJson())
		preferences.edit().putString(KEY_REPORTS, root.toString()).apply()
	}

	private companion object {

		const val PREFS_NAME = "source_diagnostics"
		const val KEY_REPORTS = "reports"
		const val FAILURE_COOLDOWN_THRESHOLD = 3
		const val FAILURE_COOLDOWN_MS = 6L * 60L * 60L * 1000L
	}
}

data class SourceDiagnostics(
	val source: String,
	val detailLoads: Int = 0,
	val recentChecks: Int = 0,
	val recentFailures: Int = 0,
	val consecutiveFailures: Int = 0,
	val recentItemsFound: Int = 0,
	val missingRating: Int = 0,
	val missingAuthor: Int = 0,
	val missingArtist: Int = 0,
	val missingStatus: Int = 0,
	val missingType: Int = 0,
	val missingDescription: Int = 0,
	val missingChapters: Int = 0,
	val missingChapterDates: Int = 0,
	val lastCheckedAt: Long = 0L,
	val lastSuccessAt: Long = 0L,
	val lastFailureAt: Long = 0L,
	val lastDurationMs: Long = 0L,
	val lastError: String? = null,
	val ratingOrigin: MetadataOrigin = MetadataOrigin.UNKNOWN,
	val authorOrigin: MetadataOrigin = MetadataOrigin.UNKNOWN,
	val typeOrigin: MetadataOrigin = MetadataOrigin.UNKNOWN,
) {

	val missingDetailsScore: Int
		get() = missingRating + missingAuthor + missingArtist + missingStatus + missingType + missingDescription

	val isSlow: Boolean
		get() = lastDurationMs >= SLOW_SOURCE_MS

	val qualityPercent: Int
		get() {
			val totalFields = detailLoads * QUALITY_FIELDS
			if (totalFields <= 0) return 0
			val missing = missingRating + missingAuthor + missingArtist + missingStatus + missingType + missingDescription
			return ((1f - (missing / totalFields.toFloat())) * 100f).roundToInt().coerceIn(0, 100)
		}

	fun recordDetails(manga: Manga, origin: MetadataOrigin, elapsedMs: Long?): SourceDiagnostics {
		val chapters = manga.chapters.orEmpty()
		val typeOrigin = manga.typeConfidence(origin)
		return copy(
			detailLoads = detailLoads + 1,
			missingRating = missingRating + if (manga.rating <= 0f) 1 else 0,
			missingAuthor = missingAuthor + if (manga.authors.firstOrNull().isNullOrBlank()) 1 else 0,
			missingArtist = missingArtist + if (manga.authors.drop(1).firstOrNull().isNullOrBlank()) 1 else 0,
			missingStatus = missingStatus + if (manga.state == null) 1 else 0,
			missingType = missingType + if (typeOrigin == MetadataOrigin.UNKNOWN) 1 else 0,
			missingDescription = missingDescription + if (manga.description.isNullOrBlank()) 1 else 0,
			missingChapters = missingChapters + if (chapters.isEmpty()) 1 else 0,
			missingChapterDates = missingChapterDates + if (chapters.isNotEmpty() && chapters.any { it.uploadDate <= 0L }) 1 else 0,
			lastDurationMs = elapsedMs ?: lastDurationMs,
			ratingOrigin = if (manga.rating > 0f) origin else MetadataOrigin.UNKNOWN,
			authorOrigin = if (manga.authors.isNotEmpty()) origin else MetadataOrigin.UNKNOWN,
			typeOrigin = typeOrigin,
		)
	}

	fun recordRecentCheck(
		now: Long,
		success: Boolean,
		itemsFound: Int,
		elapsedMs: Long,
		error: String?,
	): SourceDiagnostics = copy(
		recentChecks = recentChecks + 1,
		recentFailures = recentFailures + if (success) 0 else 1,
		consecutiveFailures = if (success) 0 else consecutiveFailures + 1,
		recentItemsFound = if (success) itemsFound else recentItemsFound,
		lastCheckedAt = now,
		lastSuccessAt = if (success) now else lastSuccessAt,
		lastFailureAt = if (success) lastFailureAt else now,
		lastDurationMs = elapsedMs,
		lastError = if (success) null else error,
	)

	fun toJson() = JSONObject()
		.put("source", source)
		.put("detailLoads", detailLoads)
		.put("recentChecks", recentChecks)
		.put("recentFailures", recentFailures)
		.put("consecutiveFailures", consecutiveFailures)
		.put("recentItemsFound", recentItemsFound)
		.put("missingRating", missingRating)
		.put("missingAuthor", missingAuthor)
		.put("missingArtist", missingArtist)
		.put("missingStatus", missingStatus)
		.put("missingType", missingType)
		.put("missingDescription", missingDescription)
		.put("missingChapters", missingChapters)
		.put("missingChapterDates", missingChapterDates)
		.put("lastCheckedAt", lastCheckedAt)
		.put("lastSuccessAt", lastSuccessAt)
		.put("lastFailureAt", lastFailureAt)
		.put("lastDurationMs", lastDurationMs)
		.put("lastError", lastError)
		.put("ratingOrigin", ratingOrigin.name)
		.put("authorOrigin", authorOrigin.name)
		.put("typeOrigin", typeOrigin.name)

	companion object {

		private const val QUALITY_FIELDS = 6
		private const val SLOW_SOURCE_MS = 10_000L

		fun fromJson(json: JSONObject) = SourceDiagnostics(
			source = json.optString("source"),
			detailLoads = json.optInt("detailLoads"),
			recentChecks = json.optInt("recentChecks"),
			recentFailures = json.optInt("recentFailures"),
			consecutiveFailures = json.optInt("consecutiveFailures"),
			recentItemsFound = json.optInt("recentItemsFound"),
			missingRating = json.optInt("missingRating"),
			missingAuthor = json.optInt("missingAuthor"),
			missingArtist = json.optInt("missingArtist"),
			missingStatus = json.optInt("missingStatus"),
			missingType = json.optInt("missingType"),
			missingDescription = json.optInt("missingDescription"),
			missingChapters = json.optInt("missingChapters"),
			missingChapterDates = json.optInt("missingChapterDates"),
			lastCheckedAt = json.optLong("lastCheckedAt"),
			lastSuccessAt = json.optLong("lastSuccessAt"),
			lastFailureAt = json.optLong("lastFailureAt"),
			lastDurationMs = json.optLong("lastDurationMs"),
			lastError = json.optString("lastError").takeIf { it.isNotBlank() && it != "null" },
			ratingOrigin = json.optOrigin("ratingOrigin"),
			authorOrigin = json.optOrigin("authorOrigin"),
			typeOrigin = json.optOrigin("typeOrigin"),
		)
	}
}

enum class MetadataOrigin {
	SOURCE_PARSER,
	SOURCE_PAGE_FALLBACK,
	INFERRED,
	SMART_MATCH,
	UNKNOWN,
}

private fun JSONObject.optOrigin(name: String): MetadataOrigin {
	return runCatching {
		MetadataOrigin.valueOf(optString(name))
	}.getOrDefault(MetadataOrigin.UNKNOWN)
}

private fun Manga.typeConfidence(defaultOrigin: MetadataOrigin): MetadataOrigin {
	if (tags.any { tag ->
			val type = tag.title.lowercase(Locale.ENGLISH)
			type == "manga" || type == "manhwa" || type == "manhua"
		}
	) {
		return if (defaultOrigin == MetadataOrigin.SOURCE_PARSER) MetadataOrigin.SOURCE_PARSER else MetadataOrigin.INFERRED
	}
	return MetadataOrigin.UNKNOWN
}
