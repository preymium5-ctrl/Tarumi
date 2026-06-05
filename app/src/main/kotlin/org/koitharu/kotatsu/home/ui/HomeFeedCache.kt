package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.os.Parcel
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.util.ext.readParcelableCompat
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class HomeFeedCache(context: Context) {

	private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	fun load(): HomeFeedSnapshot? {
		val payload = preferences.getString(KEY_SNAPSHOT, null) ?: return null
		return runCatching {
			JSONObject(payload).toSnapshot()
		}.getOrNull()
	}

	fun save(snapshot: HomeFeedSnapshot) {
		preferences.edit()
			.putString(KEY_SNAPSHOT, snapshot.toJson().toString())
			.apply()
	}

	private fun JSONObject.toSnapshot() = HomeFeedSnapshot(
		savedAt = optLong("savedAt", 0L),
		recentUpdatesSavedAt = optLong("recentUpdatesSavedAt", optLong("savedAt", 0L)),
		recentUpdatesCacheVersion = optInt("recentUpdatesCacheVersion", 0),
		recommendationPeriod = optLong("recommendationPeriod", -1L),
		featuredPeriod = optLong("featuredPeriod", -1L),
		featured = optMangaArray("featured"),
		trending = optMangaArray("trending"),
		manhuaRecommendations = optMangaArray("manhuaRecommendations"),
		mangaRecommendations = optMangaArray("mangaRecommendations"),
		smartRecommendationPeriod = optLong("smartRecommendationPeriod", -1L),
		smartRecommendations = optMangaArray("smartRecommendations"),
		recentUpdates = optJSONArray("recentUpdates").toRecentUpdates(),
	)

	private fun HomeFeedSnapshot.toJson() = JSONObject()
		.put("savedAt", savedAt)
		.put("recentUpdatesSavedAt", recentUpdatesSavedAt)
		.put("recentUpdatesCacheVersion", recentUpdatesCacheVersion)
		.put("recommendationPeriod", recommendationPeriod)
		.put("featuredPeriod", featuredPeriod)
		.put("featured", featured.toMangaArray())
		.put("trending", trending.toMangaArray())
		.put("manhuaRecommendations", manhuaRecommendations.toMangaArray())
		.put("mangaRecommendations", mangaRecommendations.toMangaArray())
		.put("smartRecommendationPeriod", smartRecommendationPeriod)
		.put("smartRecommendations", smartRecommendations.toMangaArray())
		.put("recentUpdates", recentUpdates.toRecentUpdatesArray())

	private fun JSONObject.optMangaArray(name: String): List<Manga> {
		val array = optJSONArray(name) ?: return emptyList()
		return buildList(array.length()) {
			for (i in 0 until array.length()) {
				array.optString(i, null)?.decodeManga()?.let(::add)
			}
		}
	}

	private fun List<Manga>.toMangaArray(): JSONArray = JSONArray().also { array ->
		forEach { manga ->
			array.put(manga.encodeManga())
		}
	}

	private fun JSONArray?.toRecentUpdates(): List<RecentUpdateGroup> {
		if (this == null) {
			return emptyList()
		}
		return buildList(length()) {
			for (i in 0 until length()) {
				val item = optJSONObject(i) ?: continue
				val manga = item.optString("manga", null)?.decodeManga() ?: continue
				val chapters = item.optJSONArray("chapters").toChapters()
				add(
					RecentUpdateGroup(
						manga = manga.copy(chapters = chapters),
						chapters = chapters,
						sourceTitle = item.optString("sourceTitle", manga.source.name),
						sortDate = item.optLong("sortDate", chapters.maxOfOrNull(MangaChapter::uploadDate) ?: 0L),
					),
				)
			}
		}
	}

	private fun List<RecentUpdateGroup>.toRecentUpdatesArray(): JSONArray = JSONArray().also { array ->
		forEach { group ->
			array.put(
				JSONObject()
					.put("manga", group.manga.encodeManga())
					.put("chapters", group.chapters.toChaptersArray())
					.put("sourceTitle", group.sourceTitle)
					.put("sortDate", group.sortDate),
			)
		}
	}

	private fun JSONArray?.toChapters(): List<MangaChapter> {
		if (this == null) {
			return emptyList()
		}
		return buildList(length()) {
			for (i in 0 until length()) {
				val item = optJSONObject(i) ?: continue
				add(
					MangaChapter(
						id = item.optLong("id", 0L),
						title = item.optString("title"),
						number = item.optDouble("number", 0.0).toFloat(),
						volume = item.optInt("volume", 0),
						url = item.optString("url"),
						scanlator = item.optString("scanlator", null),
						uploadDate = item.optLong("uploadDate", 0L),
						branch = item.optString("branch", null),
						source = MangaSource(item.optString("source", null)),
					),
				)
			}
		}
	}

	private fun List<MangaChapter>.toChaptersArray(): JSONArray = JSONArray().also { array ->
		forEach { chapter ->
			array.put(
				JSONObject()
					.put("id", chapter.id)
					.put("title", chapter.title)
					.put("number", chapter.number.toDouble())
					.put("volume", chapter.volume)
					.put("url", chapter.url)
					.put("scanlator", chapter.scanlator)
					.put("uploadDate", chapter.uploadDate)
					.put("branch", chapter.branch)
					.put("source", chapter.source.name),
			)
		}
	}

	private fun Manga.encodeManga(): String {
		val parcel = Parcel.obtain()
		return try {
			parcel.writeParcelable(ParcelableManga(this), 0)
			Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
		} finally {
			parcel.recycle()
		}
	}

	private fun String.decodeManga(): Manga? {
		val bytes = Base64.decode(this, Base64.NO_WRAP)
		val parcel = Parcel.obtain()
		return try {
			parcel.unmarshall(bytes, 0, bytes.size)
			parcel.setDataPosition(0)
			parcel.readParcelableCompat<ParcelableManga>()?.manga
		} finally {
			parcel.recycle()
		}
	}

	private companion object {
		const val PREFS_NAME = "tarumi_home_feed_cache"
		const val KEY_SNAPSHOT = "snapshot"
	}
}

data class HomeFeedSnapshot(
	val savedAt: Long,
	val recentUpdatesSavedAt: Long,
	val recentUpdatesCacheVersion: Int,
	val recommendationPeriod: Long,
	val featuredPeriod: Long,
	val featured: List<Manga>,
	val trending: List<Manga>,
	val manhuaRecommendations: List<Manga>,
	val mangaRecommendations: List<Manga>,
	val smartRecommendationPeriod: Long,
	val smartRecommendations: List<Manga>,
	val recentUpdates: List<RecentUpdateGroup>,
)
