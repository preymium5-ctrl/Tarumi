package org.koitharu.kotatsu.stats.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import kotlin.collections.forEach

@Dao
abstract class StatsDao {

	@Query("SELECT * FROM stats WHERE manga_id = :mangaId ORDER BY started_at")
	abstract suspend fun findAll(mangaId: Long): List<StatsEntity>

	@Query("SELECT IFNULL(SUM(pages),0) FROM stats WHERE manga_id = :mangaId")
	abstract suspend fun getReadPagesCount(mangaId: Long): Int

	@Query("SELECT IFNULL(SUM(pages),0) FROM stats")
	abstract suspend fun getTotalReadPagesCount(): Int

	@Query("SELECT IFNULL(SUM(chapters),0) FROM stats")
	abstract suspend fun getTotalReadChaptersCount(): Int

	@Query("SELECT IFNULL(SUM(duration),0) FROM stats")
	abstract suspend fun getTotalDuration(): Long

	@Query("SELECT started_at FROM stats ORDER BY started_at DESC")
	abstract suspend fun getReadTimestamps(): List<Long>

	@Query("SELECT IFNULL(SUM(duration)/SUM(pages), 0) FROM stats WHERE manga_id = :mangaId")
	abstract suspend fun getAverageTimePerPage(mangaId: Long): Long

	@Query("SELECT IFNULL(SUM(duration)/SUM(pages), 0) FROM stats")
	abstract suspend fun getAverageTimePerPage(): Long

	@Query("DELETE FROM stats")
	abstract suspend fun clear()

	@Query("SELECT COUNT(*) FROM stats WHERE manga_id = :mangaId")
	abstract fun observeRowCount(mangaId: Long): Flow<Int>

	@Upsert
	abstract suspend fun upsert(entity: StatsEntity)

	suspend fun getDurationStats(
		fromDate: Long,
		isNsfw: Boolean?,
		favouriteCategories: Set<Long>
	): Map<MangaEntity, Long> {
		val conditions = ArrayList<String>()
		conditions.add("(SELECT deleted_at FROM history WHERE history.manga_id = stats.manga_id) = 0")
		conditions.add("stats.started_at >= $fromDate")
		if (favouriteCategories.isNotEmpty()) {
			val ids = favouriteCategories.joinToString(",")
			conditions.add("stats.manga_id IN (SELECT manga_id FROM favourites WHERE category_id IN ($ids))")
		}
		if (isNsfw != null) {
			val flag = if (isNsfw) 1 else 0
			conditions.add("manga.nsfw = $flag")
		}
		val where = conditions.joinToString(separator = " AND ")
		val query = SimpleSQLiteQuery(
			"SELECT manga.*, SUM(duration) AS d FROM stats LEFT JOIN manga ON manga.manga_id = stats.manga_id WHERE $where GROUP BY manga.manga_id ORDER BY d DESC",
		)
		return getDurationStatsImpl(query)
	}

	suspend fun getChapterStats(
		fromDate: Long,
		favouriteCategories: Set<Long>
	): Map<MangaEntity, Long> {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		val query = SimpleSQLiteQuery(
			"SELECT manga.*, SUM(chapters) AS c FROM stats LEFT JOIN manga ON manga.manga_id = stats.manga_id WHERE $conditions GROUP BY manga.manga_id ORDER BY c DESC",
		)
		return getChapterStatsImpl(query)
	}

	suspend fun getGenreChapterStats(
		fromDate: Long,
		favouriteCategories: Set<Long>
	): List<GenreChapterStat> {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories) + " AND stats.chapters > 0"
		val query = SimpleSQLiteQuery(
			"""
			SELECT tags.title AS title, SUM(stats.chapters) AS chapters
			FROM stats
			LEFT JOIN manga ON manga.manga_id = stats.manga_id
			LEFT JOIN manga_tags ON manga_tags.manga_id = stats.manga_id
			LEFT JOIN tags ON tags.tag_id = manga_tags.tag_id
			WHERE $conditions AND tags.title IS NOT NULL
			GROUP BY tags.tag_id
			ORDER BY chapters DESC
			LIMIT 8
			""".trimIndent(),
		)
		return getGenreChapterStatsImpl(query)
	}

	private fun buildStatsConditions(
		fromDate: Long,
		isNsfw: Boolean?,
		favouriteCategories: Set<Long>
	): String {
		val conditions = ArrayList<String>()
		conditions.add("(SELECT deleted_at FROM history WHERE history.manga_id = stats.manga_id) = 0")
		conditions.add("stats.started_at >= $fromDate")
		if (favouriteCategories.isNotEmpty()) {
			val ids = favouriteCategories.joinToString(",")
			conditions.add("stats.manga_id IN (SELECT manga_id FROM favourites WHERE category_id IN ($ids))")
		}
		if (isNsfw != null) {
			val flag = if (isNsfw) 1 else 0
			conditions.add("manga.nsfw = $flag")
		}
		return conditions.joinToString(separator = " AND ")
	}

	@RawQuery
	protected abstract suspend fun getDurationStatsImpl(
		query: SupportSQLiteQuery
	): Map<@MapColumn("manga") MangaEntity, @MapColumn("d") Long>

	@RawQuery
	protected abstract suspend fun getChapterStatsImpl(
		query: SupportSQLiteQuery
	): Map<@MapColumn("manga") MangaEntity, @MapColumn("c") Long>

	@RawQuery
	protected abstract suspend fun getGenreChapterStatsImpl(
		query: SupportSQLiteQuery
	): List<GenreChapterStat>

	@Query("SELECT * FROM stats ORDER BY started_at LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<StatsEntity>
	fun dumpEnabled(): Flow<StatsEntity> = flow {
		val window = 10
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it) }
		}
	}
}

data class GenreChapterStat(
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "chapters") val chapters: Int,
)
