package org.koitharu.kotatsu.stats.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.history.data.HistoryEntity

/**
 * Tracks unique chapters the user has actually opened while reading.
 * Used so reading stats count each chapter once instead of re-counting
 * every time the same chapter is opened in a new session.
 */
@Entity(
	tableName = "stats_chapters",
	primaryKeys = ["manga_id", "chapter_id"],
	foreignKeys = [
		ForeignKey(
			entity = HistoryEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["manga_id"]),
		Index(value = ["read_at"]),
	],
)
data class StatsChapterEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "read_at") val readAt: Long,
)
