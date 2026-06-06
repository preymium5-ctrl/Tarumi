package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.ai.ui.AskAiLimitPrefs
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent

@AndroidEntryPoint
class AboutSettingsFragment : BasePreferenceFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_about)
		findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.run {
			title = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		}
		preferenceScreen.addPreference(
			Preference(requireContext()).apply {
				key = KEY_AI_LIMIT_OVERRIDE
				title = getString(R.string.ask_ai_override_title)
				summary = getString(R.string.ask_ai_override_summary)
				isPersistent = false
			},
		)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		combine(viewModel.isUpdateSupported, viewModel.isLoading, ::Pair)
			.observe(viewLifecycleOwner) { (isUpdateSupported, isLoading) ->
				findPreference<Preference>(AppSettings.KEY_UPDATES_UNSTABLE)?.isVisible = isUpdateSupported
				findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.isEnabled = isUpdateSupported && !isLoading

			}
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_APP_VERSION -> {
				viewModel.checkForUpdates()
				true
			}

			AppSettings.KEY_LINK_WEBLATE -> {
				openLink(R.string.url_weblate, preference.title)
				true
			}

			AppSettings.KEY_LINK_DISCORD -> {
				openLink(R.string.url_discord_invite, preference.title)
				true
			}

			AppSettings.KEY_LINK_KOFI -> {
				showSupportDialog()
				true
			}

			AppSettings.KEY_LINK_DISCORD -> {
				openLink(R.string.url_discord_invite, preference.title)
				true
			}

			AppSettings.KEY_LINK_KOFI -> {
				showSupportDialog()
				true
			}

			AppSettings.KEY_LINK_GITHUB -> {
				openLink(R.string.url_github, preference.title)
				true
			}

			AppSettings.KEY_LINK_MANUAL -> {
				openLink(R.string.url_user_manual, preference.title)
				true
			}

			KEY_AI_LIMIT_OVERRIDE -> {
				showAiLimitOverrideDialog()
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(listView, R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}

	private fun openLink(
		@StringRes url: Int,
		title: CharSequence?
	): Boolean = if (router.openExternalBrowser(getString(url), title)) {
		true
	} else {
		Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		false
	}

	private fun showSupportDialog() {
		val view = layoutInflater.inflate(R.layout.dialog_support_tarumi, null)
		val dialog = MaterialAlertDialogBuilder(requireContext())
			.setView(view)
			.create()
		view.findViewById<View>(R.id.buttonClose).setOnClickListener {
			dialog.dismiss()
		}
		view.findViewById<View>(R.id.buttonSupport).setOnClickListener {
			dialog.dismiss()
			openLink(R.string.url_kofi, getString(R.string.support_me))
		}
		dialog.show()
	}

	private fun showAiLimitOverrideDialog() {
		val input = EditText(requireContext()).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			setSingleLine()
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.ask_ai_override_title)
			.setMessage(R.string.ask_ai_override_prompt)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				if (AskAiLimitPrefs.matchesOverrideCode(input.text?.toString().orEmpty())) {
					AskAiLimitPrefs.setLimitOverrideEnabled(requireContext(), true)
					Snackbar.make(listView, R.string.ask_ai_override_enabled, Snackbar.LENGTH_SHORT).show()
				} else {
					Snackbar.make(listView, R.string.ask_ai_override_failed, Snackbar.LENGTH_SHORT).show()
				}
			}
			.show()
	}

	private companion object {
		private const val KEY_AI_LIMIT_OVERRIDE = "ask_ai_limit_override"
	}
}
