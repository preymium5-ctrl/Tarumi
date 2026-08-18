package org.koitharu.kotatsu.download.ui.list

import androidx.collection.ArrayMap
import androidx.collection.LongSet
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.collection.set
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isEmpty
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
	private val workScheduler: DownloadWorker.Scheduler,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	private val localMangaRepository: LocalMangaRepository,
) : BaseViewModel() {

	private val mangaCache = LongSparseArray<Manga>()
	private val cacheMutex = Mutex()
	private val expanded = MutableStateFlow(emptySet<UUID>())
	val isNsfwMode = MutableStateFlow(false)
	private val chaptersCache = ArrayMap<UUID, StateFlow<List<DownloadChapter>?>>()

	private val works = combine(
		workScheduler.observeWorks(),
		expanded,
	) { list, exp ->
		list.toDownloadsList(exp)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val items = combine(works, isNsfwMode) { list, nsfwMode ->
		itOrLoading(list, nsfwMode)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	val stats = combine(works, isNsfwMode) { list, nsfwMode ->
		list.orEmpty().filterDownloads(nsfwMode).toStats()
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, DownloadStatsModel.EMPTY)

	val hasPausedWorks = works.map {
		it?.any { x -> x.canResume } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasActiveWorks = works.map {
		it?.any { x -> x.canPause } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasCancellableWorks = works.map {
		it?.any { x -> !x.workState.isFinished } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	fun cancel(id: UUID) {
		launchJob(Dispatchers.Default) {
			workScheduler.cancel(id)
		}
	}

	fun cancel(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val snapshot = works.value ?: return@launchJob
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					workScheduler.cancel(work.id)
				}
			}
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun cancelAll() {
		launchJob(Dispatchers.Default) {
			workScheduler.cancelAll()
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun pause(ids: Set<Long>) {
		val snapshot = works.value ?: return
		for (work in snapshot) {
			if (work.id.mostSignificantBits in ids) {
				workScheduler.pause(work.id)
			}
		}
		onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
	}

	fun pauseAll() {
		val snapshot = works.value ?: return
		var isPaused = false
		for (work in snapshot) {
			if (work.canPause) {
				workScheduler.pause(work.id)
				isPaused = true
			}
		}
		if (isPaused) {
			onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
		}
	}

	fun resumeAll() {
		val snapshot = works.value ?: return
		var isResumed = false
		for (work in snapshot) {
			if (work.workState == WorkInfo.State.RUNNING && work.isPaused) {
				workScheduler.resume(work.id)
				isResumed = true
			}
		}
		if (isResumed) {
			onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
		}
	}

	fun resume(ids: Set<Long>) {
		val snapshot = works.value ?: return
		for (work in snapshot) {
			if (work.id.mostSignificantBits in ids) {
				workScheduler.resume(work.id)
			}
		}
		onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
	}

	fun remove(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val snapshot = works.value ?: return@launchJob
			val uuids = HashSet<UUID>(ids.size)
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					uuids.add(work.id)
				}
			}
			workScheduler.delete(uuids)
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun removeCompleted() {
		launchJob(Dispatchers.Default) {
			workScheduler.removeCompleted()
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun snapshot(ids: LongSet): Collection<DownloadItemModel> {
		return works.value?.filterTo(ArrayList(ids.size)) { x -> x.id.mostSignificantBits in ids }.orEmpty()
	}

	fun allIds(): Set<Long> = works.value?.mapToSet {
		it.id.mostSignificantBits
	} ?: emptySet()

	fun expandCollapse(item: DownloadItemModel) {
		expanded.update {
			if (item.id in it) {
				it - item.id
			} else {
				it + item.id
			}
		}
	}

	fun setNsfwMode(enabled: Boolean) {
		isNsfwMode.value = enabled
	}

	private fun itOrLoading(list: List<DownloadItemModel>?, nsfwMode: Boolean): List<ListModel> {
		return list?.filterDownloads(nsfwMode)?.toUiList() ?: listOf(LoadingState())
	}

	private fun List<DownloadItemModel>.filterDownloads(nsfwMode: Boolean): List<DownloadItemModel> {
		return filter { item ->
			val isNsfw = item.manga?.isNsfw() == true
			if (nsfwMode) isNsfw else !isNsfw
		}
	}

	private suspend fun List<WorkInfo>.toDownloadsList(exp: Set<UUID>): List<DownloadItemModel> {
		if (isEmpty()) {
			return emptyList()
		}
		val list = mapNotNullTo(ArrayList(size)) { work ->
			work.toUiModel(work.id in exp || work.state == WorkInfo.State.SUCCEEDED)
		}
		list.sortByDescending { it.timestamp }
		return list
	}

	private fun List<DownloadItemModel>.toUiList(): List<ListModel> {
		if (isEmpty()) {
			return emptyStateList()
		}
		return sortedWith(
			compareBy<DownloadItemModel> {
				when (it.workState) {
					WorkInfo.State.RUNNING -> 0
					WorkInfo.State.BLOCKED,
					WorkInfo.State.ENQUEUED -> 1

					else -> 2
				}
			}.thenByDescending { it.timestamp },
		)
	}

	private fun List<DownloadItemModel>.toStats(): DownloadStatsModel {
		var active = 0
		var completed = 0
		var chapters = 0
		for (item in this) {
			when (item.workState) {
				WorkInfo.State.RUNNING,
				WorkInfo.State.BLOCKED,
				WorkInfo.State.ENQUEUED -> active++

				WorkInfo.State.SUCCEEDED -> completed++
				else -> Unit
			}
			chapters += item.chaptersDownloaded.coerceAtLeast(0)
		}
		return DownloadStatsModel(active = active, chapters = chapters, completed = completed)
	}

	private suspend fun WorkInfo.toUiModel(isExpanded: Boolean): DownloadItemModel? {
		val workData = outputData.takeUnless { it.isEmpty }
			?: progress.takeUnless { it.isEmpty }
			?: workScheduler.getInputData(id)
			?: return null
		val mangaId = DownloadState.getMangaId(workData)
		if (mangaId == 0L) return null
		val manga = getManga(mangaId) ?: return null
		val baseChapters = synchronized(chaptersCache) {
			chaptersCache.getOrPut(id) {
				observeChapters(manga, id)
			}
		}
		// Overlay live per-chapter progress from WorkManager progress data
		// (works for online + offline once chapters are known).
		val chapters = mapChaptersWithProgress(
			base = baseChapters,
			workState = state,
			workData = workData,
		)
		return DownloadItemModel(
			id = id,
			workState = state,
			manga = manga,
			error = DownloadState.getError(workData),
			isIndeterminate = DownloadState.isIndeterminate(workData),
			isPaused = DownloadState.isPaused(workData),
			max = DownloadState.getMax(workData),
			progress = DownloadState.getProgress(workData),
			eta = DownloadState.getEta(workData),
			isStuck = DownloadState.isStuck(workData),
			timestamp = DownloadState.getTimestamp(workData),
			chaptersDownloaded = DownloadState.getDownloadedChapters(workData),
			isExpanded = isExpanded,
			chapters = chapters,
		)
	}

	private fun mapChaptersWithProgress(
		base: StateFlow<List<DownloadChapter>?>,
		workState: WorkInfo.State,
		workData: androidx.work.Data,
	): StateFlow<List<DownloadChapter>?> {
		val downloadedCount = DownloadState.getDownloadedChapters(workData)
		val currentChapter = DownloadState.getCurrentChapter(workData)
		val totalPages = DownloadState.getTotalPages(workData).coerceAtLeast(0)
		val currentPage = DownloadState.getCurrentPage(workData).coerceAtLeast(0)
		val isRunning = workState == WorkInfo.State.RUNNING && !DownloadState.isPaused(workData)
		return base.map { list ->
			if (list.isNullOrEmpty()) return@map list
			list.mapIndexed { index, chapter ->
				when {
					chapter.isDownloaded || index < downloadedCount -> chapter.copy(
						isDownloaded = true,
						progress = 1f,
						isActive = false,
					)
					isRunning && index == currentChapter -> {
						val pageProgress = if (totalPages > 0) {
							((currentPage + 1).toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
						} else {
							0f
						}
						chapter.copy(
							progress = pageProgress,
							isActive = true,
							isDownloaded = false,
						)
					}
					else -> chapter.copy(progress = 0f, isActive = false)
				}
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, base.value)
	}

	private fun emptyStateList() = listOf(
		EmptyState(
			icon = R.drawable.cat_yarn,
			textPrimary = R.string.text_downloads_list_holder,
			textSecondary = 0,
			actionStringRes = 0,
		),
	)

	private suspend fun getManga(mangaId: Long): Manga? {
		mangaCache[mangaId]?.let {
			return it
		}
		return cacheMutex.withLock {
			mangaCache.getOrElse(mangaId) {
				mangaDataRepository.findMangaById(mangaId, withChapters = true)?.also {
					mangaCache[mangaId] = it
				} ?: return null
			}
		}
	}

	private fun observeChapters(manga: Manga, workId: UUID): StateFlow<List<DownloadChapter>?> = flow {
		val chapterIds = workScheduler.getTask(workId)?.chaptersIds
		// Prefer local/DB chapters first so offline + NSFW downloads still show a list.
		val chapters = resolveChapters(manga) ?: return@flow

		suspend fun mapChapters(): List<DownloadChapter> {
			val size = chapterIds?.size ?: chapters.size
			val localChapters =
				localMangaRepository.findSavedManga(manga)?.manga?.chapters?.mapToSet { it.id }.orEmpty()
			return chapters.mapNotNullTo(ArrayList(size)) {
				if (chapterIds == null || it.id in chapterIds) {
					DownloadChapter(
						number = it.numberString(),
						name = it.name,
						isDownloaded = it.id in localChapters,
						progress = if (it.id in localChapters) 1f else 0f,
					)
				} else {
					null
				}
			}
		}
		emit(mapChapters())
		localStorageChanges.collect {
			if (it?.manga?.id == manga.id) {
				emit(mapChapters())
			}
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private suspend fun resolveChapters(manga: Manga): List<org.koitharu.kotatsu.parsers.model.MangaChapter>? {
		manga.chapters?.takeIf { it.isNotEmpty() }?.let { return it }
		localMangaRepository.findSavedManga(manga, withDetails = true)?.manga?.chapters
			?.takeIf { it.isNotEmpty() }
			?.let { return it }
		mangaDataRepository.findMangaById(manga.id, withChapters = true)?.chapters
			?.takeIf { it.isNotEmpty() }
			?.let { return it }
		return tryLoad(manga)?.chapters?.takeIf { it.isNotEmpty() }
	}

	private suspend fun tryLoad(manga: Manga) = runCatchingCancellable {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}.getOrNull()
}

data class DownloadStatsModel(
	val active: Int,
	val chapters: Int,
	val completed: Int,
) {
	companion object {
		val EMPTY = DownloadStatsModel(active = 0, chapters = 0, completed = 0)
	}
}
