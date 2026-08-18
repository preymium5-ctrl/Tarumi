package org.koitharu.kotatsu.stats.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
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

	@Query("DELETE FROM stats_chapters")
	abstract suspend fun clearChapters()

	@Query("SELECT COUNT(*) FROM stats WHERE manga_id = :mangaId")
	abstract fun observeRowCount(mangaId: Long): Flow<Int>

	@Upsert
	abstract suspend fun upsert(entity: StatsEntity)

	/**
	 * Records a chapter as read. Returns true when this chapter was not tracked before
	 * (so callers can increment session chapter counters only for first-time opens).
	 */
	suspend fun tryInsertChapter(mangaId: Long, chapterId: Long, readAt: Long): Boolean {
		val inserted = insertChapter(
			StatsChapterEntity(
				mangaId = mangaId,
				chapterId = chapterId,
				readAt = readAt,
			),
		)
		return inserted != -1L
	}

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertChapter(entity: StatsChapterEntity): Long

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

	/**
	 * Chapters read per manga for the period.
	 *
	 * Raw SUM(stats.chapters) over-counts whenever the same chapter is opened in multiple
	 * sessions. Cap each manga by history progress (percent × total chapters) so a title
	 * you only finished ~64 chapters of cannot show 100+.
	 *
	 * When unique chapter rows exist for the period, prefer MAX(unique, capped-sum) so new
	 * accurate tracking is used without under-counting older inflated-but-capped data.
	 */
	suspend fun getChapterStats(
		fromDate: Long,
		favouriteCategories: Set<Long>
	): Map<MangaEntity, Long> {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		val query = SimpleSQLiteQuery(
			"""
			SELECT manga.*, ${chapterCountExpression(fromDate)} AS c
			FROM stats
			LEFT JOIN manga ON manga.manga_id = stats.manga_id
			LEFT JOIN history ON history.manga_id = stats.manga_id
			WHERE $conditions
			GROUP BY manga.manga_id
			ORDER BY c DESC
			""".trimIndent(),
		)
		return getChapterStatsImpl(query)
	}

	suspend fun getGenreChapterStats(
		fromDate: Long,
		favouriteCategories: Set<Long>
	): List<GenreChapterStat> {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		val query = SimpleSQLiteQuery(
			"""
			SELECT tags.title AS title, SUM(manga_chapters.chapters) AS chapters
			FROM (
				SELECT stats.manga_id AS manga_id, ${chapterCountExpression(fromDate)} AS chapters
				FROM stats
				LEFT JOIN history ON history.manga_id = stats.manga_id
				WHERE $conditions
				GROUP BY stats.manga_id
			) AS manga_chapters
			LEFT JOIN manga_tags ON manga_tags.manga_id = manga_chapters.manga_id
			LEFT JOIN tags ON tags.tag_id = manga_tags.tag_id
			WHERE tags.title IS NOT NULL AND manga_chapters.chapters > 0
			GROUP BY tags.tag_id
			ORDER BY chapters DESC
			LIMIT 8
			""".trimIndent(),
		)
		return getGenreChapterStatsImpl(query)
	}

	/**
	 * Per-manga chapter count expression used inside GROUP BY stats.manga_id queries.
	 * history and stats must be available in the outer query.
	 */
	private fun chapterCountExpression(fromDate: Long): String = """
		MAX(
			IFNULL((
				SELECT COUNT(*) FROM stats_chapters sc
				WHERE sc.manga_id = stats.manga_id AND sc.read_at >= $fromDate
			), 0),
			MIN(
				IFNULL(SUM(stats.chapters), 0),
				CASE
					WHEN history.chapters > 0 AND history.percent >= 0
					THEN MAX(1, CAST(ROUND(history.percent * history.chapters) AS INTEGER))
					ELSE IFNULL(SUM(stats.chapters), 0)
				END
			)
		)
	""".trimIndent()

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

	suspend fun getSummaryPages(fromDate: Long, favouriteCategories: Set<Long>): Int {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		val query = SimpleSQLiteQuery("SELECT IFNULL(SUM(pages), 0) FROM stats WHERE $conditions")
		return getSummaryPagesImpl(query)
	}

	suspend fun getSummaryChapters(fromDate: Long, favouriteCategories: Set<Long>): Int {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		// Sum of per-manga capped chapter counts — never raw SUM which double-counts re-reads.
		val query = SimpleSQLiteQuery(
			"""
			SELECT IFNULL(SUM(manga_chapters.chapters), 0) FROM (
				SELECT stats.manga_id AS manga_id, ${chapterCountExpression(fromDate)} AS chapters
				FROM stats
				LEFT JOIN history ON history.manga_id = stats.manga_id
				WHERE $conditions
				GROUP BY stats.manga_id
			) AS manga_chapters
			""".trimIndent(),
		)
		return getSummaryChaptersImpl(query)
	}

	suspend fun getSummaryDuration(fromDate: Long, favouriteCategories: Set<Long>): Long {
		val conditions = buildStatsConditions(fromDate, null, favouriteCategories)
		val query = SimpleSQLiteQuery("SELECT IFNULL(SUM(duration), 0) FROM stats WHERE $conditions")
		return getSummaryDurationImpl(query)
	}

	@RawQuery
	protected abstract suspend fun getSummaryPagesImpl(query: SupportSQLiteQuery): Int

	@RawQuery
	protected abstract suspend fun getSummaryChaptersImpl(query: SupportSQLiteQuery): Int

	@RawQuery
	protected abstract suspend fun getSummaryDurationImpl(query: SupportSQLiteQuery): Long

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
