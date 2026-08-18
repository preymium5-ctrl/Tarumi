package org.koitharu.kotatsu.favourites.ui.categories

import android.content.Context
import android.graphics.Color
import org.koitharu.kotatsu.R

data class FavoriteStatusColors(
	val background: Int,
	val stroke: Int,
	val foreground: Int,
	val accent: Int
) {
	companion object {
		fun getStyle(context: Context, status: String?, isChecked: Boolean = true): FavoriteStatusColors {
			if (!isChecked) {
				return FavoriteStatusColors(
					background = context.getColor(R.color.taru_surface_button),
					stroke = context.getColor(R.color.taru_outline),
					foreground = context.getColor(R.color.taru_text_primary),
					accent = context.getColor(R.color.taru_accent)
				)
			}
			return when (status?.trim()?.lowercase()) {
				"reading" -> FavoriteStatusColors(
					background = 0xFFE7F1FF.toInt(),
					stroke = 0xFF74A7FF.toInt(),
					foreground = 0xFF0B56B3.toInt(),
					accent = 0xFF3B82F6.toInt()
				)
				"plan to read", "planned", "planning" -> FavoriteStatusColors(
					background = 0xFFF0ECFF.toInt(),
					stroke = 0xFF8D7BFF.toInt(),
					foreground = 0xFF5142C4.toInt(),
					accent = 0xFF6D5DF6.toInt()
				)
				"completed" -> FavoriteStatusColors(
					background = 0xFFEAF8EF.toInt(),
					stroke = 0xFF62BE7B.toInt(),
					foreground = 0xFF1F7A3F.toInt(),
					accent = 0xFF2EA557.toInt()
				)
				"rereading", "repeating" -> FavoriteStatusColors(
					background = 0xFFFFF0F5.toInt(),
					stroke = 0xFFFFB5C5.toInt(),
					foreground = 0xFFC71585.toInt(),
					accent = 0xFFE21D87.toInt()
				)
				"paused", "on hold", "onhold", "on_hold" -> FavoriteStatusColors(
					background = 0xFFFFFBE6.toInt(),
					stroke = 0xFFFFD700.toInt(),
					foreground = 0xFF8B7500.toInt(),
					accent = 0xFFD4AF37.toInt()
				)
				"dropped" -> FavoriteStatusColors(
					background = 0xFFFFEBEA.toInt(),
					stroke = 0xFFFF8D85.toInt(),
					foreground = 0xFFB31412.toInt(),
					accent = 0xFFE23A4D.toInt()
				)
				"remove" -> FavoriteStatusColors(
					background = 0xFFFFEEF0.toInt(),
					stroke = 0xFFFF9AA7.toInt(),
					foreground = 0xFFC92A3C.toInt(),
					accent = 0xFFE23A4D.toInt()
				)
				else -> FavoriteStatusColors(
					background = context.getColor(R.color.taru_surface_button),
					stroke = context.getColor(R.color.taru_outline),
					foreground = context.getColor(R.color.taru_text_primary),
					accent = context.getColor(R.color.taru_accent)
				)
			}
		}

		fun getColor(status: String?): Int {
			return when (status?.trim()?.lowercase()) {
				"completed" -> Color.parseColor("#1F7A3F")
				"reading" -> Color.parseColor("#0B56B3")
				"plan to read", "planned", "planning" -> Color.parseColor("#5142C4")
				"rereading", "repeating" -> Color.parseColor("#C71585")
				"paused", "on hold", "onhold", "on_hold" -> Color.parseColor("#8B7500")
				"dropped" -> Color.parseColor("#B31412")
				"remove" -> Color.parseColor("#C92A3C")
				else -> Color.parseColor("#5F58B6")
			}
		}
	}
}
