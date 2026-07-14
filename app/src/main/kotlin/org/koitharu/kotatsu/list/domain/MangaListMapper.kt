package org.koitharu.kotatsu.list.domain

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.IntDef
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import javax.inject.Inject

@Reusable
class MangaListMapper @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val dataRepository: MangaDataRepository,
	private val db: MangaDatabase,
) {


	suspend fun toListModelList(
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): List<MangaListModel> = ArrayList<MangaListModel>(manga.size).apply {
		toListModelList(
			destination = this,
			manga = manga,
			mode = mode,
			flags = flags,
		)
	}

	suspend fun toListModelList(
		destination: MutableCollection<in MangaListModel>,
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		pinnedIds: Set<Long> = emptySet(),
		showRemoveAction: Boolean = false,
	) {
		val options = getOptions(flags)
		val overrides = dataRepository.getOverrides()
		manga.mapTo(destination) {
			toListModelImpl(it, mode, options, overrides[it.id], it.id in pinnedIds, showRemoveAction)
		}
	}

	suspend fun toListModel(
		manga: Manga,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): MangaListModel = toListModelImpl(
		manga = manga,
		mode = mode,
		options = getOptions(flags),
		override = dataRepository.getOverride(manga.id),
	)

	suspend fun toFeedItem(logItem: TrackingLogItem) = FeedItem(
		id = logItem.id,
		override = dataRepository.getOverride(logItem.manga.id),
		chapters = logItem.chapters,
		createdAt = logItem.createdAt,
		count = logItem.chapters.size,
		manga = logItem.manga,
		isNew = logItem.isNew,
	)

	fun mapTags(tags: Collection<MangaTag>) = tags.map {
		ChipsView.ChipModel(
			tint = getTagTint(it),
			title = it.title,
			data = it,
		)
	}

	private suspend fun toCompactListModel(
		manga: Manga,
		@Options options: Int,
		override: MangaOverride?,
	) = MangaCompactListModel(
		manga = manga,
		override = override,
		subtitle = manga.tags.joinToString(", ") { it.title },
		counter = getCounter(manga.id, options),
	)

	private suspend fun toDetailedListModel(
		manga: Manga,
		@Options options: Int,
		override: MangaOverride?,
		isPinned: Boolean = false,
		showRemoveAction: Boolean = false,
	): MangaDetailedListModel {
		val counter = getCounter(manga.id, options)
		val progress = getProgress(manga.id, options)
		val history = historyRepository.getOne(manga)
		val mangaWithChapters = if (manga.chapters.isNullOrEmpty()) {
			dataRepository.findMangaById(manga.id, withChapters = true) ?: manga
		} else {
			manga
		}
		val chapters = mangaWithChapters.chapters.orEmpty()
		val branch = mangaWithChapters.getPreferredBranch(history)
		val branchChapters = mangaWithChapters.getChapters(branch).orEmpty().ifEmpty { chapters }
		val currentChapter = history?.let { h ->
			branchChapters.findById(h.chapterId) ?: chapters.findById(h.chapterId)
		}
		val latestChapter = branchChapters.latestChapter()
		val currentChapterIndex = currentChapter?.let { chapter ->
			branchChapters.indexOfFirst { it.id == chapter.id }.takeIf { it >= 0 }?.plus(1)
		}
		// Prefer the freshest total: chapter cache can lag behind the tracker NEW counter
		// until details are re-fetched, while history.chaptersCount is updated on track hits.
		val listChapterCount = mangaWithChapters.chaptersCount()
		val historyChapterCount = history?.chaptersCount ?: 0
		val progressChapterCount = progress?.totalChapters ?: 0
		// When NEW chapters exist but the local list is still short, grow the total by the
		// unread counter so "current / total" matches the new release.
		val trackedTotal = if (counter > 0 && listChapterCount > 0) {
			maxOf(listChapterCount, (currentChapterIndex ?: 0) + counter)
		} else {
			0
		}
		val totalChapters = maxOf(listChapterCount, historyChapterCount, progressChapterCount, trackedTotal)
		val currentChapterNumber = currentChapter?.numberString()
			?: currentChapterIndex?.toString()
			?: progress?.chapters?.takeIf { it > 0 }?.toString()
		val latestChapterNumber = latestChapter?.numberString()
			?: totalChapters.takeIf { it > 0 && (listChapterCount == 0 || totalChapters > listChapterCount) }?.toString()
		val scrobble = db.getScrobblingDao().findAllByMangaId(manga.id).firstOrNull()
		return MangaDetailedListModel(
			subtitle = manga.altTitles.firstOrNull(),
			manga = manga,
			override = override,
			counter = counter,
			progress = progress,
			isFavorite = isFavorite(manga.id, options),
			isSaved = isSaved(manga.id, options),
			tags = mapTags(manga.tags),
			isPinned = isPinned,
			latestChapterTitle = latestChapter?.getLocalizedTitle(context.resources)
				?: latestChapterNumber?.let { "Chapter $it" },
			currentChapterTitle = currentChapter?.getLocalizedTitle(context.resources),
			latestChapterAge = latestChapter?.uploadDate?.toInstantOrNull()
				?.let { calculateTimeAgo(it, showMonths = true)?.format(context) },
			currentReadAge = history?.updatedAt?.let { calculateTimeAgo(it, showMonths = true)?.format(context) },
			latestChapterNumber = latestChapterNumber,
			currentChapterNumber = currentChapterNumber,
			totalChapters = totalChapters,
			totalChapterLabel = latestChapterNumber ?: totalChapters.takeIf { it > 0 }?.toString(),
			canContinue = history != null && (!ReadingProgress.isCompleted(history.percent) || counter > 0),
			statusTitle = favouritesRepository.getStatusTitle(manga.id),
			showRemoveAction = showRemoveAction,
			trackerStatus = scrobble?.status,
			trackerChapter = scrobble?.chapter?.takeIf { it > 0 },
			trackerRating = scrobble?.rating,
		)
	}

	private fun List<org.koitharu.kotatsu.parsers.model.MangaChapter>.latestChapter() =
		filter { it.uploadDate > 0L }.maxByOrNull { it.uploadDate }
			?: filter { it.number > 0 }.maxByOrNull { it.number }
			?: lastOrNull()

	private suspend fun toGridModel(
		manga: Manga,
		@Options options: Int,
		override: MangaOverride?,
		isPinned: Boolean = false,
	) = MangaGridModel(
		manga = manga,
		override = override,
		counter = getCounter(manga.id, options),
		progress = getProgress(manga.id, options),
		isFavorite = isFavorite(manga.id, options),
		isSaved = isSaved(manga.id, options),
		isPinned = isPinned,
	)

	private suspend fun toListModelImpl(
		manga: Manga,
		mode: ListMode,
		@Options options: Int,
		override: MangaOverride?,
		isPinned: Boolean = false,
		showRemoveAction: Boolean = false,
	): MangaListModel = when (mode) {
		ListMode.LIST -> toCompactListModel(manga, options, override)
		ListMode.DETAILED_LIST -> toDetailedListModel(manga, options, override, isPinned, showRemoveAction)
		ListMode.GRID -> toGridModel(manga, options, override, isPinned)
	}

	private suspend fun getCounter(mangaId: Long, @Options options: Int): Int {
		return if (settings.isTrackerEnabled) {
			trackingRepository.getNewChaptersCount(mangaId)
		} else {
			0
		}
	}

	private suspend fun getProgress(mangaId: Long, @Options options: Int): ReadingProgress? {
		return if (options.isBadgeEnabled(PROGRESS)) {
			historyRepository.getProgress(mangaId, settings.progressIndicatorMode)
		} else {
			null
		}
	}

	private suspend fun isFavorite(mangaId: Long, @Options options: Int): Boolean {
		return options.isBadgeEnabled(FAVORITE) && favouritesRepository.isFavorite(mangaId)
	}

	private suspend fun isSaved(mangaId: Long, @Options options: Int): Boolean {
		return options.isBadgeEnabled(SAVED) && mangaId in localMangaIndex
	}

	@ColorRes
	private fun getTagTint(tag: MangaTag): Int {
		return 0
	}


	private fun Int.isBadgeEnabled(@Options badge: Int) = this and badge == badge

	@Options
	@SuppressLint("WrongConstant")
	private fun getOptions(@Flags flags: Int): Int {
		var options = settings.getMangaListBadges() or PROGRESS
		options = options and flags.inv()
		return options
	}

	@IntDef(DEFAULTS, NO_SAVED, NO_PROGRESS, NO_FAVORITE, flag = true)
	@Retention(AnnotationRetention.SOURCE)
	annotation class Flags

	@IntDef(NONE, SAVED, FAVORITE, PROGRESS)
	@Retention(AnnotationRetention.SOURCE)
	private annotation class Options

	companion object {

		private const val NONE = 0
		private const val SAVED = 1
		private const val PROGRESS = 2
		private const val FAVORITE = 4

		const val DEFAULTS = NONE
		const val NO_SAVED = SAVED
		const val NO_PROGRESS = PROGRESS
		const val NO_FAVORITE = FAVORITE
	}
}
