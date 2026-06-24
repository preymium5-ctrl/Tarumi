package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.settings.search.SettingsSearchMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchViewModel

@AndroidEntryPoint
class RootSettingsFragment : BasePreferenceFragment(0) {

	private val viewModel: RootSettingsViewModel by viewModels()
	private val activityViewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_root)
		addPreferencesFromResource(R.xml.pref_root_debug)
		preferenceScreen.isOrderingAsAdded = false
		bindPreferenceSummary("appearance", R.string.theme, R.string.list_mode, R.string.language)
		bindPreferenceSummary("security", R.string.protect_application)
		bindPreferenceSummary("reader", R.string.read_mode, R.string.scale_mode, R.string.switch_pages)
		bindPreferenceSummary("network", R.string.storage_usage, R.string.proxy, R.string.prefetch_content)
		bindPreferenceSummary("userdata", R.string.create_or_restore_backup, R.string.periodic_backups)
		bindPreferenceSummary("downloads", R.string.manga_save_location, R.string.downloads_wifi_only)
		bindPreferenceSummary("tracker", R.string.track_sources, R.string.notifications_settings)
		bindPreferenceSummary("services", R.string.suggestions, R.string.sync, R.string.tracking)
		findPreference<Preference>("check_app_updates")?.summary = getString(R.string.check_for_updates)
		findPreference<Preference>("local_storage")?.setOnPreferenceClickListener {
			router.openList(LocalMangaSource, null, null)
			true
		}
		findPreference<Preference>("reading_stats")?.setOnPreferenceClickListener {
			router.openStatistic()
			true
		}
		findPreference<Preference>(AppSettings.KEY_SOURCE_HEALTH)?.setOnPreferenceClickListener {
			router.openSourceHealthSystemChecker()
			true
		}
		findPreference<Preference>(AppSettings.KEY_METADATA_QUALITY)?.setOnPreferenceClickListener {
			router.openMetadataQualityDashboard()
			true
		}
		findPreference<Preference>("about")?.summary = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		findPreference<Preference>("check_app_updates")?.order = TARUMI_UPDATES_ORDER
		findPreference<Preference>("about")?.order = TARUMI_ABOUT_PREF_ORDER
		findPreference<Preference>("tarumi_about_card")?.let { pref ->
			pref.summary = buildString {
				append(getString(R.string.app_version, BuildConfig.VERSION_NAME))
				append("\n\nDeveloper\nRJS - Taru")
			}
			pref.order = TARUMI_ABOUT_ORDER
		}
		findPreference<Preference>("debug")?.order = TARUMI_DEBUG_ORDER
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.let { pref ->
			val total = viewModel.totalSourcesCount
			viewModel.enabledSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = if (it >= 0) {
					getString(R.string.enabled_d_of_d, it, total)
				} else {
					resources.getQuantityStringSafe(R.plurals.items, total, total)
				}
			}
		}
		addMenuProvider(SettingsSearchMenuProvider(activityViewModel))
	}

	override fun setTitle(title: CharSequence?) {
		if (!resources.getBoolean(R.bool.is_tablet)) {
			super.setTitle(title)
		}
	}

	private fun bindPreferenceSummary(key: String, @StringRes vararg items: Int) {
		findPreference<Preference>(key)?.summary = items.joinToString { getString(it) }
	}

	private companion object {

		const val TARUMI_UPDATES_ORDER = 997
		const val TARUMI_DEBUG_ORDER = 998
		const val TARUMI_ABOUT_PREF_ORDER = 999
		const val TARUMI_ABOUT_ORDER = 1000
	}
}
