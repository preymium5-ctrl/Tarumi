package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.putEnumValue
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.settings.utils.validation.DomainValidator
import java.io.File

class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {

	private val sourceName = source.name
	private val prefs = context.getSharedPreferences(
		source.name.replace(File.separatorChar, '$'),
		Context.MODE_PRIVATE,
	)

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

	val isSlowdownEnabled: Boolean
		get() = prefs.getBoolean(KEY_SLOWDOWN, false)

	val isCaptchaNotificationsDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

	val isCaptchaAutoResolveDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_AUTO_CAPTCHA, false) && sourceName !in FORCE_AUTO_CAPTCHA_SOURCES

	/** User-Agent that earned the current cf_clearance cookie; Cloudflare binds clearance to it. */
	var cloudFlareUserAgent: String?
		get() = prefs.getString(KEY_CF_USER_AGENT, null)?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_CF_USER_AGENT, value?.sanitizeHeaderValue()) }

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		return when (key) {
			is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			is ConfigKey.Domain -> {
				var domain = prefs.getString(key.key, key.defaultValue)
					?.trim()
					?.takeIf { DomainValidator.isValidDomain(it) }
					?: key.defaultValue
				// manhuafast.com / .net are currently offline; auto-switch to the working temporary mirror.
				if (sourceName in MANHUAFAST_SOURCES && domain in DEAD_MANHUAFAST_DOMAINS) {
					domain = key.defaultValue
					prefs.edit { putString(key.key, domain) }
				}
				domain
			}

			is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
			is ConfigKey.DisableUpdateChecking -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.InterceptCloudflare -> prefs.getBoolean(key.key, key.defaultValue)
		} as T
	}

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
            is ConfigKey.InterceptCloudflare -> putBoolean(key.key, value as Boolean)
			is ConfigKey.DisableUpdateChecking -> {
				// Read-only - parser-controlled only, users cannot change this
			}
		}
	}

	fun subscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	companion object {

		const val KEY_DOMAIN = "domain"
		const val KEY_NO_CAPTCHA = "no_captcha"
		const val KEY_NO_AUTO_CAPTCHA = "no_auto_captcha"
		const val KEY_SLOWDOWN = "slowdown"
		const val KEY_SORT_ORDER = "sort_order"
		const val KEY_CF_USER_AGENT = "cf_user_agent"

		private val FORCE_AUTO_CAPTCHA_SOURCES = setOf(
			"HENTAI3Z",
			"HENTAI3ZCC",
			"MANHUAFAST",
			"MANGAFASTNET",
		)

		private val MANHUAFAST_SOURCES = setOf(
			"MANHUAFAST",
			"MANGAFASTNET",
		)

		private val DEAD_MANHUAFAST_DOMAINS = setOf(
			"manhuafast.com",
			"manhuafast.net",
			"www.manhuafast.com",
			"www.manhuafast.net",
		)
	}
}
