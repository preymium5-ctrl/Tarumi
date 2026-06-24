package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.toFavouriteCategory
import org.koitharu.kotatsu.favourites.data.toMangaList
import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingConsentStore
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import javax.inject.Inject

@Reusable
class FavouritesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val localObserver: LocalFavoritesObserver,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val scrobblingConsentStore: ScrobblingConsentStore,
) {

	suspend fun getAllManga(): List<Manga> {
		val entities = db.getFavouritesDao().findAll()
		return entities.toMangaList()
	}

	suspend fun getLastManga(limit: Int): List<Manga> {
		val entities = db.getFavouritesDao().findLast(limit)
		return entities.toMangaList()
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val dao = db.getFavouritesDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
		}
		return entities.toMangaList()
	}

	fun observeAll(order: ListSortOrder, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Manga>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(order, filterOptions, limit)
			.map { it.toMangaList() }
	}

	suspend fun getManga(categoryId: Long): List<Manga> {
		val entities = db.getFavouritesDao().findAll(categoryId)
		return entities.toMangaList()
	}

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Manga>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(categoryId, order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit)
			.map { it.toMangaList() }
	}

	fun observeAll(categoryId: Long, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Manga>> {
		return observeOrder(categoryId)
			.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }
	}

	fun observeMangaCount(): Flow<Int> {
		return db.getFavouritesDao().observeMangaCount()
			.distinctUntilChanged()
	}

	suspend fun findPopularTagTitles(categoryId: Long, limit: Int): List<String> {
		return db.getFavouritesDao().run {
			if (categoryId == 0L) {
				findPopularTagTitles(limit)
			} else {
				findPopularTagTitles(categoryId, limit)
			}
		}
	}

	fun observeCategories(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAll().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	fun observeCategoriesForLibrary(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAllVisible().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	suspend fun ensureTarumiStatusCategories(categories: List<FavouriteCategory>): List<FavouriteCategory> {
		val result = ArrayList(categories)
		for (title in TARUMI_STATUS_TITLES) {
			if (result.none { isStatusCategoryEquivalent(it.title, title) }) {
				result += createCategory(
					title = title,
					sortOrder = ListSortOrder.NEWEST,
					isTrackerEnabled = true,
					isVisibleOnShelf = true,
				)
			}
		}
		return listOf(TARUMI_REMOVE_CATEGORY) + TARUMI_STATUS_TITLES.mapNotNull { title ->
			result.firstOrNull { isStatusCategoryEquivalent(it.title, title) }
		}
	}

	private fun isStatusCategoryEquivalent(categoryTitle: String, targetStatusTitle: String): Boolean {
		if (categoryTitle.equals(targetStatusTitle, ignoreCase = true)) return true
		if (targetStatusTitle.equals("Plan to read", ignoreCase = true)) {
			val legacy = listOf("Planned", "Read later", "Planning to read", "Save")
			return legacy.any { it.equals(categoryTitle, ignoreCase = true) }
		}
		return false
	}

	suspend fun setStatusCategory(
		categoryId: Long,
		statusCategoryIds: Set<Long>,
		mangas: Collection<Manga>,
	) {
		db.withTransaction {
			val dao = db.getFavouritesDao()
			for (manga in mangas) {
				for (id in statusCategoryIds) {
					dao.delete(mangaId = manga.id, categoryId = id)
				}
			}
			if (categoryId == TARUMI_REMOVE_CATEGORY.id) {
				return@withTransaction
			}
			for (manga in mangas) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				dao.insert(
					FavouriteEntity(
						mangaId = manga.id,
						categoryId = categoryId,
						createdAt = System.currentTimeMillis(),
						sortKey = 0,
						deletedAt = 0L,
						isPinned = false,
					),
				)
			}
		}
		if (categoryId != TARUMI_REMOVE_CATEGORY.id) {
			for (manga in mangas) {
				if (scrobblingConsentStore.getConsent(manga.id) == ScrobblingConsentStore.Consent.UNDECIDED) {
					scrobblingConsentStore.setConsent(manga.id, ScrobblingConsentStore.Consent.ENABLED)
				}
				for (scrobbler in scrobblers) {
					if (scrobbler.isEnabled && !scrobblingConsentStore.isServiceBlocked(manga.id, scrobbler.scrobblerService)) {
						runCatchingCancellable {
							scrobbler.ensureMangaLinked(manga, allowAutoLink = true)
						}.onFailure {
							it.printStackTraceDebug()
						}
					}
				}
			}
		}
	}

	fun observeCategoriesWithCovers(): Flow<Map<FavouriteCategory, List<Cover>>> {
		return db.invalidationTracker.createFlow(
			TABLE_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val categories = db.getFavouriteCategoriesDao().findAll()
				val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
				for (entity in categories) {
					val cat = entity.toFavouriteCategory()
					res[cat] = db.getFavouritesDao().findCovers(
						categoryId = cat.id,
						order = cat.order,
					)
				}
				res
			}
		}.distinctUntilChanged()
	}

	suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
		return db.getFavouritesDao().findCovers(order, limit)
	}

	fun observeCategory(id: Long): Flow<FavouriteCategory?> {
		return db.getFavouriteCategoriesDao().observe(id)
			.map { it?.toFavouriteCategory() }
	}

	fun observeCategoriesIds(mangaId: Long): Flow<Set<Long>> {
		return db.getFavouritesDao().observeIds(mangaId).map { it.toSet() }
	}

	fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return db.getFavouritesDao().observeCategories(mangaId).map {
			it.mapTo(LinkedHashSet(it.size)) { x -> x.toFavouriteCategory() }
		}
	}

	suspend fun getCategory(id: Long): FavouriteCategory {
		return db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()
	}

	suspend fun isFavorite(mangaId: Long): Boolean {
		return db.getFavouritesDao().findCategoriesCount(mangaId) != 0
	}

	suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
		return db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
	}

	suspend fun getStatusTitle(mangaId: Long): String? {
		val categoryIds = db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
		if (categoryIds.isEmpty()) {
			return null
		}
		return db.getFavouriteCategoriesDao().findAll()
			.firstOrNull { entity ->
				entity.categoryId.toLong() in categoryIds &&
					TARUMI_STATUS_TITLES.any { it.equals(entity.title, ignoreCase = true) }
			}
			?.title
	}

	suspend fun getStatusCategoryIds(includeLegacy: Boolean): Set<Long> {
		val titles = if (includeLegacy) {
			TARUMI_STATUS_TITLES + TARUMI_LEGACY_STATUS_TITLES
		} else {
			TARUMI_STATUS_TITLES
		}
		return db.getFavouriteCategoriesDao().findAll()
			.filter { entity ->
				titles.any { it.equals(entity.title, ignoreCase = true) }
			}
			.mapTo(HashSet()) { it.categoryId.toLong() }
	}

	suspend fun findPopularSources(categoryId: Long, limit: Int): List<MangaSource> {
		return db.getFavouritesDao().run {
			if (categoryId == 0L) {
				findPopularSources(limit)
			} else {
				findPopularSources(categoryId, limit)
			}
		}.toMangaSources()
	}

	suspend fun createCategory(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	): FavouriteCategory {
		val entity = FavouriteCategoryEntity(
			title = title,
			createdAt = System.currentTimeMillis(),
			sortKey = db.getFavouriteCategoriesDao().getNextSortKey(),
			categoryId = 0,
			order = sortOrder.name,
			track = isTrackerEnabled,
			deletedAt = 0L,
			isVisibleInLibrary = isVisibleOnShelf,
		)
		val id = db.getFavouriteCategoriesDao().insert(entity)
		val category = entity.toFavouriteCategory(id)
		return category
	}

	suspend fun updateCategory(
		id: Long,
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		db.getFavouriteCategoriesDao().update(id, title, sortOrder.name, isTrackerEnabled, isVisibleOnShelf)
	}

	suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
		db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
	}

	suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
		db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
	}

	suspend fun removeCategories(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().deleteAll(id)
				db.getFavouriteCategoriesDao().delete(id)
			}
			db.getChaptersDao().gc()
		}
	}

	companion object {

		val TARUMI_STATUS_TITLES = listOf(
			"Reading",
			"Plan to read",
			"Completed",
			"Rereading",
			"Paused",
			"Dropped",
		)

		val TARUMI_REMOVE_CATEGORY = FavouriteCategory(
			id = 0L,
			title = "Remove",
			sortKey = -1,
			order = ListSortOrder.NEWEST,
			createdAt = java.time.Instant.EPOCH,
			isTrackingEnabled = false,
			isVisibleInLibrary = false,
		)

		private val TARUMI_LEGACY_STATUS_TITLES = listOf(
			"Planned",
			"Read later",
			"Planning to read",
			"Save",
		)
	}

	suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
		db.getFavouriteCategoriesDao().updateOrder(id, order.name)
	}

	suspend fun reorderCategories(orderedIds: List<Long>) {
		val dao = db.getFavouriteCategoriesDao()
		db.withTransaction {
			for ((i, id) in orderedIds.withIndex()) {
				dao.updateSortKey(id, i)
			}
		}
	}

	suspend fun addToCategory(categoryId: Long, mangas: Collection<Manga>) {
		db.withTransaction {
			for (manga in mangas) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				val entity = FavouriteEntity(
					mangaId = manga.id,
					categoryId = categoryId,
					createdAt = System.currentTimeMillis(),
					sortKey = 0,
					deletedAt = 0L,
					isPinned = false,
				)
				db.getFavouritesDao().insert(entity)
			}
		}
	}

	suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().delete(mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToFavourites(ids) }
	}

	suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().delete(categoryId = categoryId, mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToCategory(categoryId, ids) }
	}

	suspend fun setPinned(mangaIds: Collection<Long>, categoryId: Long, isPinned: Boolean) {
		db.withTransaction {
			val dao = db.getFavouritesDao()
			for (id in mangaIds) {
				if (categoryId == 0L) {
					dao.setPinned(id, isPinned)
				} else {
					dao.setPinned(id, categoryId, isPinned)
				}
			}
		}
	}

	suspend fun getPinnedIds(categoryId: Long): Set<Long> {
		val dao = db.getFavouritesDao()
		return if (categoryId == 0L) {
			dao.findAllPinnedIds().toSet()
		} else {
			dao.findPinnedIds(categoryId).toSet()
		}
	}

	private fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
		return db.getFavouriteCategoriesDao().observe(categoryId)
			.filterNotNull()
			.map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
			.distinctUntilChanged()
	}

	suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
		return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
			it.toFavouriteCategory()
		}
	}

	private suspend fun recoverToFavourites(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id)
			}
		}
	}

	private suspend fun recoverToCategory(categoryId: Long, ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id, categoryId = categoryId)
			}
		}
	}

	suspend fun syncLibraryFromTracker(scrobbler: Scrobbler) {
		if (!scrobbler.isEnabled) return
		val allCategories = db.getFavouriteCategoriesDao().findAll().map { it.toFavouriteCategory() }
		val statusCategories = ensureTarumiStatusCategories(allCategories)
		val statusCategoryMap = statusCategories.associateBy { it.title }
		val statusCategoryIds = statusCategories.map { it.id }.toSet()

		val trackerEntries = scrobbler.getUserMangaList()
		val dao = db.getFavouritesDao()
		val scrobblingDao = db.getScrobblingDao()
		val mangaDao = db.getMangaDao()

		for (entry in trackerEntries) {
			val scrobblingEntity = scrobblingDao.findByTarget(scrobbler.scrobblerService.id, entry.targetId)
			var mangaId: Long? = scrobblingEntity?.mangaId

			if (mangaId == null) {
				val localManga = mangaDao.findByTitle(entry.title) ?: mangaDao.findByTitleLike("%${entry.title}%")
				if (localManga != null) {
					mangaId = localManga.manga.id
				}
			}

			if (mangaId == null) {
				val dummyUrl = "dummy_tracker://${scrobbler.scrobblerService.id}/${entry.targetId}"
				val placeholderManga = org.koitharu.kotatsu.core.db.entity.MangaEntity(
					id = 0,
					url = dummyUrl,
					publicUrl = dummyUrl,
					source = "TrackerPlaceholder",
					largeCoverUrl = null,
					coverUrl = entry.coverUrl.orEmpty(),
					altTitles = null,
					rating = -1f,
					isNsfw = false,
					contentRating = null,
					state = null,
					title = entry.title,
					authors = null,
				)
				mangaId = mangaDao.insert(placeholderManga)
			}

			if (mangaId != null) {
				val remoteStatus = entry.status
				val scrobblingStatus = getScrobblingStatusFromRemote(scrobbler, remoteStatus)
				val categoryName = getCategoryNameFromStatus(scrobblingStatus)
				val targetCategory = categoryName?.let { statusCategoryMap[it] }

				if (targetCategory != null) {
					val entity = org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity(
						scrobbler = scrobbler.scrobblerService.id,
						id = entry.id,
						mangaId = mangaId,
						targetId = entry.targetId,
						status = entry.status,
						chapter = entry.chapter,
						comment = entry.comment,
						rating = entry.rating,
					)
					scrobblingDao.upsert(entity)

					db.withTransaction {
						for (catId in statusCategoryIds) {
							dao.delete(mangaId = mangaId, categoryId = catId)
						}
						dao.insert(
							FavouriteEntity(
								mangaId = mangaId,
								categoryId = targetCategory.id,
								createdAt = System.currentTimeMillis(),
								sortKey = 0,
								deletedAt = 0L,
								isPinned = false,
							)
						)
					}

					if (scrobblingConsentStore.getConsent(mangaId) == ScrobblingConsentStore.Consent.UNDECIDED) {
						scrobblingConsentStore.setConsent(mangaId, ScrobblingConsentStore.Consent.ENABLED)
					}
				}
			}
		}
	}

	private fun getScrobblingStatusFromRemote(scrobbler: Scrobbler, remoteStatus: String?): org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus? {
		if (remoteStatus == null) return null
		for (status in org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.values()) {
			if (scrobbler.getRemoteStatus(status).equals(remoteStatus, ignoreCase = true)) {
				return status
			}
		}
		return null
	}

	private fun getCategoryNameFromStatus(status: org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus?): String? {
		return when (status) {
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.READING -> "Reading"
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.PLANNED -> "Plan to read"
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.COMPLETED -> "Completed"
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.RE_READING -> "Rereading"
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.ON_HOLD -> "Paused"
			org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus.DROPPED -> "Dropped"
			else -> null
		}
	}
}
