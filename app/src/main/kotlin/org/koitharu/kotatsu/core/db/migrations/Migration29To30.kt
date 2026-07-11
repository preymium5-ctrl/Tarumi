package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration29To30 : Migration(29, 30) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `stats_chapters` (
				`manga_id` INTEGER NOT NULL,
				`chapter_id` INTEGER NOT NULL,
				`read_at` INTEGER NOT NULL,
				PRIMARY KEY(`manga_id`, `chapter_id`),
				FOREIGN KEY(`manga_id`) REFERENCES `history`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_stats_chapters_manga_id` ON `stats_chapters` (`manga_id`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_stats_chapters_read_at` ON `stats_chapters` (`read_at`)")
	}
}
