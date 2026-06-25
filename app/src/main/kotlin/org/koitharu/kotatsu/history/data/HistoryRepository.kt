package org.koitharu.kotatsu.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingConsentStore
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingConsentStore.Consent
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.tryScrobble
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class HistoryRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val scrobblingConsentStore: ScrobblingConsentStore,
	private val mangaRepository: MangaDataRepository,
	private val localObserver: HistoryLocalObserver,
	private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
) {

	suspend fun getList(offset: Int, limit: Int): List<Manga> {
		val entities = db.getHistoryDao().findAll(offset, limit)
		return entities.map { it.toManga() }
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val dao = db.getHistoryDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
		}
		return entities.toMangaList()
	}

	suspend fun getLastOrNull(): Manga? {
		val entity = db.getHistoryDao().findAll(0, 1).firstOrNull() ?: return null
		return entity.toManga()
	}

	fun observeLast(): Flow<Manga?> {
		return db.getHistoryDao().observeAll(1).map {
			val first = it.firstOrNull()
			first?.toManga()
		}
	}

	fun observeAll(): Flow<List<Manga>> {
		return db.getHistoryDao().observeAll().mapItems {
			it.toManga()
		}
	}

	fun observeAll(limit: Int): Flow<List<Manga>> {
		return db.getHistoryDao().observeAll(limit).mapItems {
			it.toManga()
		}
	}

	fun observeAllWithHistory(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<MangaWithHistory>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getHistoryDao().observeAll(order, filterOptions, limit).mapItems {
			MangaWithHistory(
				it.toManga(),
				it.history.toMangaHistory(),
			)
		}
	}

	fun observeOne(id: Long): Flow<MangaHistory?> {
		return db.getHistoryDao().observe(id).map {
			it?.toMangaHistory()
		}
	}

	suspend fun addOrUpdate(
		manga: Manga,
		chapterId: Long,
		page: Int,
		scroll: Int,
		percent: Float,
		force: Boolean,
		updateScrobbling: Boolean = true,
	) {
		if (!force && shouldSkip(manga)) {
			return
		}
		assert(manga.chapters != null)
		db.withTransaction {
			mangaRepository.storeManga(manga, replaceExisting = true)
			val branch = manga.chapters?.findById(chapterId)?.branch
			db.getHistoryDao().upsert(
				HistoryEntity(
					mangaId = manga.id,
					createdAt = System.currentTimeMillis(),
					updatedAt = System.currentTimeMillis(),
					chapterId = chapterId,
					page = page,
					scroll = scroll.toFloat(), // we migrate to int, but decide to not update database
					percent = percent,
					chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
					deletedAt = 0L,
				),
			)
			newChaptersUseCaseProvider.get()(manga, chapterId)
			val consent = scrobblingConsentStore.getConsent(manga.id)
			if (updateScrobbling && consent != Consent.DISABLED) {
				scrobblers.forEach { scrobbler ->
					if (!scrobblingConsentStore.isServiceBlocked(manga.id, scrobbler.scrobblerService)) {
						scrobbler.tryScrobble(
							manga = manga,
							chapterId = chapterId,
							allowAutoLink = consent == Consent.ENABLED,
						)
					}
				}
			}
		}
	}

	suspend fun shouldAskForScrobbling(mangaId: Long): Boolean {
		if (scrobblingConsentStore.getConsent(mangaId) != Consent.UNDECIDED) return false
		val enabledScrobblers = scrobblers.filter { it.isEnabled }
		if (enabledScrobblers.isEmpty()) return false
		return enabledScrobblers.none { it.isMangaLinked(mangaId) }
	}

	suspend fun setAutomaticScrobbling(manga: Manga, chapterId: Long, enabled: Boolean): MangaHistory? {
		scrobblingConsentStore.setConsent(
			mangaId = manga.id,
			consent = if (enabled) Consent.ENABLED else Consent.DISABLED,
		)
		if (!enabled) {
			return null
		}
		val linkedScrobblers = scrobblers.filter { scrobbler ->
			scrobbler.isEnabled &&
				!scrobblingConsentStore.isServiceBlocked(manga.id, scrobbler.scrobblerService) &&
				runCatchingCancellable {
					scrobbler.ensureMangaLinked(manga, allowAutoLink = true)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(false)
		}
		return syncScrobblingProgress(manga, chapterId, linkedScrobblers)
	}

	suspend fun syncScrobblingProgress(
		manga: Manga,
		currentChapterId: Long?,
		targetScrobblers: Collection<Scrobbler>,
	): MangaHistory? {
		if (targetScrobblers.isEmpty()) {
			return null
		}
		val target = resolveSyncTarget(manga, currentChapterId, targetScrobblers) ?: return null
		val history = getOne(manga)
		val updatedHistory = if (target.historyChapter != null && (
				history == null ||
					target.readChapters > getChapterProgress(manga, history.chapterId) ||
					manga.chapters?.findById(history.chapterId) == null
				)
		) {
			addOrUpdate(
				manga = manga,
				chapterId = target.historyChapter.id,
				page = 0,
				scroll = 0,
				percent = target.percent,
				force = true,
				updateScrobbling = false,
			)
			getOne(manga)
		} else {
			null
		}
		targetScrobblers.forEach { scrobbler ->
			if (!scrobblingConsentStore.isServiceBlocked(manga.id, scrobbler.scrobblerService)) {
				scrobbler.tryScrobble(manga, target.scrobbleChapter.id, allowAutoLink = false)
			}
		}
		return updatedHistory
	}

	suspend fun getOne(manga: Manga): MangaHistory? {
		return db.getHistoryDao().find(manga.id)?.recoverIfNeeded(manga)?.toMangaHistory()
	}

	suspend fun findSimilarByTitle(manga: Manga, limit: Int): List<MangaHistory> {
		if (limit <= 0) {
			return emptyList()
		}
		val titles = (sequenceOf(manga.title) + manga.altTitles.asSequence())
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
		val result = linkedMapOf<Long, MangaHistory>()
		for (title in titles) {
			if (result.size >= limit) {
				break
			}
			db.getHistoryDao().findSimilarByTitle(
				mangaId = manga.id,
				title = title,
				altTitleQuery = "%$title%",
				limit = limit - result.size,
			).forEach { entity ->
				result.putIfAbsent(entity.mangaId, entity.toMangaHistory())
			}
		}
		return result.values.toList()
	}

	suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
		val entity = db.getHistoryDao().find(mangaId) ?: return null
		val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
		return ReadingProgress(
			percent = fixedPercent,
			totalChapters = entity.chaptersCount,
			mode = mode,
		).takeIf { it.isValid() }
	}

	suspend fun clear() {
		db.getHistoryDao().clear()
	}

	suspend fun delete(manga: Manga) = db.withTransaction {
		db.getHistoryDao().delete(manga.id)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteAfter(minDate: Long) = db.withTransaction {
		db.getHistoryDao().deleteAfter(minDate)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteNotFavorite() = db.withTransaction {
		db.getHistoryDao().deleteNotFavorite()
		mangaRepository.gcChaptersCache()
	}

	suspend fun delete(ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getHistoryDao().delete(id)
			}
			mangaRepository.gcChaptersCache()
		}
		return ReversibleHandle {
			recover(ids)
		}
	}

	/**
	 * Try to replace one manga with another one
	 * Useful for replacing saved manga on deleting it with remote source
	 */
	suspend fun deleteOrSwap(manga: Manga, alternative: Manga?) {
		if (alternative == null || db.getMangaDao().update(alternative.toEntity()) <= 0) {
			delete(manga)
		}
	}

	suspend fun getPopularTags(limit: Int): List<MangaTag> {
		return db.getHistoryDao().findPopularTags(limit).toMangaTagsList()
	}

	suspend fun getPopularSources(limit: Int): List<MangaSource> {
		return db.getHistoryDao().findPopularSources(limit).toMangaSources()
	}

	fun shouldSkip(manga: Manga): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

	fun observeShouldSkip(manga: Manga): Flow<Boolean> {
		return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
			.map { shouldSkip(manga) }
			.distinctUntilChanged()
	}

	private suspend fun recover(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getHistoryDao().recover(id)
			}
		}
	}

	private suspend fun resolveSyncTarget(
		manga: Manga,
		currentChapterId: Long?,
		targetScrobblers: Collection<Scrobbler>,
	): ScrobblingSyncTarget? {
		val chapters = getSyncChapters(manga, currentChapterId)
		if (chapters.isEmpty()) {
			return null
		}
		val history = getOne(manga)
		val localReadChapters = history?.chapterId?.let { getChapterProgress(chapters, it) } ?: 0
		val remoteReadChapters = targetScrobblers.maxOfOrNull { scrobbler ->
			runCatchingCancellable {
				scrobbler.getTrackedChapterOrNull(manga.id) ?: 0
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(0)
		} ?: 0
		val readChapters = maxOf(remoteReadChapters, localReadChapters)
		val currentChapter = currentChapterId?.let { id -> chapters.findById(id) }
			?: history?.chapterId?.let { id -> chapters.findById(id) }
			?: chapters.firstOrNull()
			?: return null
		val scrobbleChapter = findScrobbleChapter(chapters, readChapters) ?: currentChapter
		val historyChapter = if (remoteReadChapters > localReadChapters) {
			findHistoryChapter(chapters, remoteReadChapters)
		} else {
			null
		}
		return ScrobblingSyncTarget(
			readChapters = readChapters,
			historyChapter = historyChapter,
			scrobbleChapter = scrobbleChapter,
			percent = if (chapters.isEmpty()) {
				ReadingProgress.PROGRESS_NONE
			} else {
				(remoteReadChapters / chapters.size.toFloat()).coerceIn(0f, 1f)
			},
		)
	}

	private fun getSyncChapters(manga: Manga, currentChapterId: Long?): List<MangaChapter> {
		val allChapters = manga.chapters.orEmpty()
		val branch = currentChapterId?.let { id -> allChapters.findById(id)?.branch }
			?: manga.getPreferredBranch(null)
		return manga.getChapters(branch).ifEmpty { allChapters }
	}

	private fun parseChapterNumberFromName(name: String): Double? {
		val match = Regex("""\b\d+(?:\.\d+)?\b""").find(name)
		return match?.value?.toDoubleOrNull()
	}

	private fun findHistoryChapter(chapters: List<MangaChapter>, readChapters: Int): MangaChapter? {
		if (chapters.isEmpty()) {
			return null
		}
		val exactNumberMatch = chapters.firstOrNull { it.number.toInt() == readChapters }
		if (exactNumberMatch != null) {
			return exactNumberMatch
		}
		val exactNameMatch = chapters.firstOrNull { chapter ->
			parseChapterNumberFromName(chapter.title.orEmpty())?.toInt() == readChapters
		}
		if (exactNameMatch != null) {
			return exactNameMatch
		}
		if (hasChapterNumbers(chapters)) {
			return chapters.firstOrNull { it.number.toInt() > readChapters } ?: chapters.last()
		}
		return chapters[readChapters.coerceIn(0, chapters.lastIndex)]
	}

	private fun findScrobbleChapter(chapters: List<MangaChapter>, readChapters: Int): MangaChapter? {
		if (readChapters <= 0 || chapters.isEmpty()) {
			return null
		}
		val exactNumberMatch = chapters.firstOrNull { it.number.toInt() == readChapters }
		if (exactNumberMatch != null) {
			return exactNumberMatch
		}
		val exactNameMatch = chapters.firstOrNull { chapter ->
			parseChapterNumberFromName(chapter.title.orEmpty())?.toInt() == readChapters
		}
		if (exactNameMatch != null) {
			return exactNameMatch
		}
		if (hasChapterNumbers(chapters)) {
			return chapters.lastOrNull { it.number.toInt() <= readChapters } ?: chapters.first()
		}
		return chapters[(readChapters - 1).coerceIn(0, chapters.lastIndex)]
	}

	private fun getChapterProgress(manga: Manga, chapterId: Long): Int {
		return getChapterProgress(getSyncChapters(manga, chapterId), chapterId)
	}

	private fun getChapterProgress(chapters: List<MangaChapter>, chapterId: Long): Int {
		val chapter = chapters.findById(chapterId) ?: return 0
		return if (chapter.number > 0f) {
			chapter.number.toInt()
		} else {
			chapters.indexOf(chapter) + 1
		}
	}

	private fun hasChapterNumbers(chapters: List<MangaChapter>): Boolean {
		return chapters.any { it.number > 0f }
	}

	private suspend fun HistoryEntity.recoverIfNeeded(manga: Manga): HistoryEntity {
		val chapters = manga.chapters
		if (manga.isLocal || chapters.isNullOrEmpty() || chapters.findById(chapterId) != null) {
			return this
		}
		val newChapterId = chapters.getOrNull(
			(chapters.size * percent).toInt(),
		)?.id ?: return this
		val newEntity = copy(chapterId = newChapterId)
		db.getHistoryDao().update(newEntity)
		return newEntity
	}

	private fun HistoryWithManga.toManga() = manga.toManga(tags.toMangaTags(), chapters)

	private data class ScrobblingSyncTarget(
		val readChapters: Int,
		val historyChapter: MangaChapter?,
		val scrobbleChapter: MangaChapter,
		val percent: Float,
	)
}
