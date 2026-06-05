package org.koitharu.kotatsu.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class StatsRepository @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
) {

	suspend fun getReadingStats(period: StatsPeriod, categories: Set<Long>): List<StatsRecord> {
		val fromDate = if (period == StatsPeriod.ALL) {
			0L
		} else {
			System.currentTimeMillis() - TimeUnit.DAYS.toMillis(period.days.toLong())
		}
		val stats = db.getStatsDao().getDurationStats(fromDate, null, categories)
		val result = ArrayList<StatsRecord>(stats.size)
		var other = StatsRecord(null, 0)
		val total = stats.values.sum()
		for ((mangaEntity, duration) in stats) {
			val manga = mangaEntity.toManga(emptySet(), null)
			val percent = duration.toDouble() / total
			if (percent < 0.05) {
				other = other.copy(duration = other.duration + duration)
			} else {
				result += StatsRecord(
					manga = manga,
					duration = duration,
				)
			}
		}
		if (other.duration != 0L) {
			result += other
		}
		return result
	}

	suspend fun getTimePerPage(mangaId: Long): Long = db.withTransaction {
		val dao = db.getStatsDao()
		val pages = dao.getReadPagesCount(mangaId)
		val time = if (pages >= 10) {
			dao.getAverageTimePerPage(mangaId)
		} else {
			dao.getAverageTimePerPage()
		}
		time
	}

	suspend fun getTotalPagesRead(mangaId: Long): Int {
		return db.getStatsDao().getReadPagesCount(mangaId)
	}

	suspend fun getSummaryStats(): ReadingStatsSummary {
		val dao = db.getStatsDao()
		val timestamps = dao.getReadTimestamps()
		return ReadingStatsSummary(
			streakDays = timestamps.currentReadingStreak(),
			totalPages = dao.getTotalReadPagesCount(),
			totalDuration = dao.getTotalDuration(),
		)
	}

	suspend fun getMangaTimeline(mangaId: Long): NavigableMap<Long, Int> {
		val entities = db.getStatsDao().findAll(mangaId)
		val map = TreeMap<Long, Int>()
		for (e in entities) {
			map[e.startedAt] = e.pages
		}
		return map
	}

	suspend fun clearStats() {
		db.getStatsDao().clear()
	}

	fun observeHasStats(mangaId: Long): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_STATS_ENABLED) {
		isStatsEnabled
	}.flatMapLatest { isEnabled ->
		if (isEnabled) {
			db.getStatsDao().observeRowCount(mangaId).map { it > 0 }
		} else {
			flowOf(false)
		}
	}.distinctUntilChanged()

	private fun List<Long>.currentReadingStreak(): Int {
		if (isEmpty()) {
			return 0
		}
		val zone = ZoneId.systemDefault()
		val days = mapTo(HashSet(size)) {
			Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
		}.sortedDescending()
		val today = LocalDate.now(zone)
		var cursor = when (days.first()) {
			today -> today
			today.minusDays(1) -> today.minusDays(1)
			else -> return 0
		}
		var streak = 0
		for (day in days) {
			when {
				day == cursor -> {
					streak++
					cursor = cursor.minusDays(1)
				}
				day.isBefore(cursor) -> break
			}
		}
		return streak
	}
}

data class ReadingStatsSummary(
	val streakDays: Int,
	val totalPages: Int,
	val totalDuration: Long,
)
