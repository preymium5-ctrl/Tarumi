package org.koitharu.kotatsu.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.settings.protect.ProtectSetupActivity
import javax.inject.Inject

@AndroidEntryPoint
class SecuritySettingsFragment :
    BasePreferenceFragment(R.string.security),
    SharedPreferences.OnSharedPreferenceChangeListener {


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_security)
        findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
            ?.isChecked = !settings.appPassword.isNullOrEmpty()

        findPreference<EditTextPreference>(AppSettings.KEY_APP_RECOVERY_WORD)?.let { pref ->
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                if (p.text.isNullOrEmpty()) {
                    getString(R.string.recovery_word_not_set)
                } else {
                    getString(R.string.recovery_word_set)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(this)
    }

    override fun onResume() {
        super.onResume()
        findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
            ?.isChecked = !settings.appPassword.isNullOrEmpty()
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }


    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == AppSettings.KEY_APP_PASSWORD) {
            findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
                ?.isChecked = !settings.appPassword.isNullOrEmpty()
        }
    }

    override fun onPreferenceTreeClick(preference: androidx.preference.Preference): Boolean {
        return when (preference.key) {
            AppSettings.KEY_PROTECT_APP -> {
                val pref = (preference as? TwoStatePreference ?: return false)
                if (pref.isChecked) {
                    pref.isChecked = false
                    startActivity(Intent(preference.context, ProtectSetupActivity::class.java))
                } else {
                    settings.appPassword = null
                }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }
}
