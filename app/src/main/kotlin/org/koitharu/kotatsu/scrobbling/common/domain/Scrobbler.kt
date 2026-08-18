package org.koitharu.kotatsu.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap

abstract class Scrobbler(
	protected val db: MangaDatabase,
	val scrobblerService: ScrobblerService,
	private val repository: ScrobblerRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private val infoCache = LongSparseArray<ScrobblerMangaInfo>()
	protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

	val user: Flow<ScrobblerUser?> = flow {
		val cached = repository.cachedUser
		if (cached != null) {
			emit(cached)
		} else {
			emit(null)
		}
		if (repository.isAuthorized) {
			runCatchingCancellable {
				repository.loadUser()
			}.onSuccess {
				emit(it)
			}.onFailure {
				it.printStackTraceDebug()
				if (cached == null) {
					emit(null)
				}
			}
		}
	}

	val isEnabled: Boolean
		get() = repository.isAuthorized

	suspend fun authorize(authCode: String): ScrobblerUser {
		repository.authorize(authCode)
		return repository.loadUser()
	}

	fun logout() {
		repository.logout()
	}

	suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
		return repository.findManga(query, offset)
	}

	fun getRemoteStatus(status: ScrobblingStatus): String? {
		return statuses[status]
	}

	suspend fun getUserMangaList(): List<org.koitharu.kotatsu.scrobbling.common.data.TrackerMangaEntry> {
		return repository.getUserMangaList()
	}

	suspend fun linkManga(mangaId: Long, targetId: Long) {
		repository.createRate(mangaId, targetId)
	}

	suspend fun isMangaLinked(mangaId: Long): Boolean {
		return db.getScrobblingDao().find(scrobblerService.id, mangaId) != null
	}

	suspend fun ensureMangaLinked(manga: Manga, allowAutoLink: Boolean): Boolean {
		if (isMangaLinked(manga.id)) return true
		if (!allowAutoLink || !isEnabled) return false
		val searchResults = runCatchingCancellable {
			findManga(manga.title, 0)
		}.getOrNull()
		val match = searchResults?.firstOrNull { it.isBestMatch } ?: searchResults?.firstOrNull()
		if (match != null) {
			return runCatchingCancellable {
				linkManga(manga.id, match.id)
				true
			}.getOrDefault(false)
		}
		return false
	}

	suspend fun getTrackedChapterOrNull(mangaId: Long): Int? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		if (!isEnabled) return entity.chapter
		return runCatchingCancellable {
			val rate = repository.getExistingRate(entity.targetId)
			if (rate != null) {
				db.getScrobblingDao().upsert(
					ScrobblingEntity(
						scrobbler = entity.scrobbler,
						id = rate.id,
						mangaId = entity.mangaId,
						targetId = entity.targetId,
						status = rate.status,
						chapter = rate.chapter,
						comment = rate.comment,
						rating = rate.rating,
					)
				)
				rate.chapter
			} else {
				entity.chapter
			}
		}.getOrElse {
			it.printStackTraceDebug()
			entity.chapter
		}
	}

	suspend fun getExistingRate(manga: Manga): org.koitharu.kotatsu.scrobbling.common.data.ExistingRate? {
		if (!isEnabled) return null
		val searchResults = runCatchingCancellable {
			findManga(manga.title, 0)
		}.getOrNull() ?: return null
		val match = searchResults.firstOrNull { it.isBestMatch } ?: searchResults.firstOrNull() ?: return null
		return runCatchingCancellable {
			repository.getExistingRate(match.id)
		}.getOrNull()
	}

	suspend fun linkMangaWithExistingRate(mangaId: Long, rate: org.koitharu.kotatsu.scrobbling.common.data.ExistingRate) {
		db.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = scrobblerService.id,
				id = rate.id,
				mangaId = mangaId,
				targetId = rate.targetId,
				status = rate.status,
				chapter = rate.chapter,
				comment = rate.comment,
				rating = rate.rating,
			)
		)
	}

	suspend fun scrobble(manga: Manga, chapterId: Long, allowAutoLink: Boolean = false) {
		var chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
		}
		requireNotNull(chapters)
		val chapter = checkNotNull(chapters.findById(chapterId)) {
			"Chapter $chapterId not found in this manga"
		}
		val number = if (chapter.number > 0f) {
			chapter.number.toInt()
		} else {
			chapters = chapters.filter { x -> x.branch == chapter.branch }
			chapters.indexOf(chapter) + 1
		}
		var entity = db.getScrobblingDao().find(scrobblerService.id, manga.id)
		if (entity == null && allowAutoLink) {
			ensureMangaLinked(manga, allowAutoLink = true)
			entity = db.getScrobblingDao().find(scrobblerService.id, manga.id)
		}
		if (entity == null) return
		repository.updateRate(entity.id, entity.mangaId, number)
	}

	suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return entity.toScrobblingInfo()
	}

	abstract suspend fun updateScrobblingInfo(
		mangaId: Long,
		@FloatRange(from = 0.0, to = 1.0) rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
	)

	fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
		return db.getScrobblingDao().observe(scrobblerService.id, mangaId)
			.map { it?.toScrobblingInfo() }
	}

	fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
		return db.getScrobblingDao().observe(scrobblerService.id)
			.map { entities ->
				coroutineScope {
					entities.map {
						async {
							it.toScrobblingInfo()
						}
					}.awaitAll()
				}.filterNotNull()
			}
	}

	suspend fun unregisterScrobbling(mangaId: Long) {
		repository.unregister(mangaId)
	}

	protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		return repository.getMangaInfo(id)
	}

	private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
		val mangaInfo = infoCache.getOrElse(targetId) {
			runCatchingCancellable {
				getMangaInfo(targetId)
			}.onFailure {
				it.printStackTraceDebug()
			}.onSuccess {
				infoCache.put(targetId, it)
			}.getOrNull() ?: return null
		}
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			mangaId = mangaId,
			targetId = targetId,
			status = statuses.findKeyByValue(status),
			chapter = chapter,
			comment = comment,
			rating = rating,
			title = mangaInfo.name,
			coverUrl = mangaInfo.cover,
			description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
			externalUrl = mangaInfo.url,
		)
	}
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long, allowAutoLink: Boolean = false): Boolean {
	return runCatchingCancellable {
		scrobble(manga, chapterId, allowAutoLink)
	}.onFailure {
		it.printStackTraceDebug()
	}.isSuccess
}
