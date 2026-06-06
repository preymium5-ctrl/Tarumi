package org.koitharu.kotatsu.ai.ui

import android.content.Context
import androidx.core.content.edit

object AskAiLimitPrefs {

	const val PREFS_NAME = "ask_ai_history"
	const val KEY_LIMIT_OVERRIDE = "limit_override"

	private val overrideCodeChars = intArrayOf(65, 68, 77, 73, 78, 74, 65, 75, 69)

	fun isLimitOverrideEnabled(context: Context): Boolean {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.getBoolean(KEY_LIMIT_OVERRIDE, false)
	}

	fun setLimitOverrideEnabled(context: Context, enabled: Boolean) {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit { putBoolean(KEY_LIMIT_OVERRIDE, enabled) }
	}

	fun matchesOverrideCode(input: String): Boolean {
		val expected = overrideCodeChars.joinToString(separator = "") { it.toChar().toString() }
		return input.trim().equals(expected, ignoreCase = true)
	}
}
