package org.koitharu.kotatsu.scrobbling.common.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers whether automatic progress updates were accepted for a manga.
 *
 * This intentionally lives outside Room: the choice is UI preference state, not remote tracking data,
 * and can be consulted before a manga has been linked to any service.
 */
@Singleton
class ScrobblingConsentStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun getConsent(mangaId: Long): Consent = when (preferences.getString(consentKey(mangaId), null)) {
		VALUE_ENABLED -> Consent.ENABLED
		VALUE_DISABLED -> Consent.DISABLED
		else -> Consent.UNDECIDED
	}

	fun setConsent(mangaId: Long, consent: Consent) {
		preferences.edit {
			when (consent) {
				Consent.UNDECIDED -> remove(consentKey(mangaId))
				Consent.ENABLED -> putString(consentKey(mangaId), VALUE_ENABLED)
				Consent.DISABLED -> putString(consentKey(mangaId), VALUE_DISABLED)
			}
		}
	}

	fun isServiceBlocked(mangaId: Long, service: ScrobblerService): Boolean {
		return preferences.getBoolean(serviceKey(mangaId, service), false)
	}

	fun setServiceBlocked(mangaId: Long, service: ScrobblerService, blocked: Boolean) {
		preferences.edit {
			if (blocked) {
				putBoolean(serviceKey(mangaId, service), true)
			} else {
				remove(serviceKey(mangaId, service))
			}
		}
	}

	private fun consentKey(mangaId: Long) = "consent_$mangaId"

	private fun serviceKey(mangaId: Long, service: ScrobblerService) = "blocked_${mangaId}_${service.id}"

	enum class Consent {
		UNDECIDED,
		ENABLED,
		DISABLED,
	}

	private companion object {
		const val PREFERENCES_NAME = "scrobbling_consent"
		const val VALUE_ENABLED = "enabled"
		const val VALUE_DISABLED = "disabled"
	}
}
