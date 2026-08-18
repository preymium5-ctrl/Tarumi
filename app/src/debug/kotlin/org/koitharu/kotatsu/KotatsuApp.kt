package org.koitharu.kotatsu

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.StrictMode
import androidx.core.content.edit
import androidx.fragment.app.strictmode.FragmentStrictMode
import leakcanary.LeakCanary
import org.koitharu.kotatsu.core.BaseApp

class KotatsuApp : BaseApp() {

	var isLeakCanaryEnabled: Boolean
		get() = getDebugPreferences(this).getBoolean(KEY_LEAK_CANARY, false)
		set(value) {
			getDebugPreferences(this).edit { putBoolean(KEY_LEAK_CANARY, value) }
			configureLeakCanary()
		}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		enableStrictMode()
		configureLeakCanary()
	}

	private fun configureLeakCanary() {
		LeakCanary.config = LeakCanary.config.copy(
			dumpHeap = isLeakCanaryEnabled,
		)
	}

	private fun enableStrictMode() {
		StrictMode.setThreadPolicy(
			StrictMode.ThreadPolicy.Builder().apply {
				detectNetwork()
				detectDiskWrites()
				detectCustomSlowCalls()
				detectResourceMismatches()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectUnbufferedIo()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) detectExplicitGc()
				penaltyLog()
			}.build(),
		)
		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder().apply {
				detectActivityLeaks()
				detectLeakedSqlLiteObjects()
				detectLeakedClosableObjects()
				detectLeakedRegistrationObjects()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					detectContentUriWithoutPermission()
				}
				detectFileUriExposure()
				penaltyLog()
			}.build(),
		)
		FragmentStrictMode.defaultPolicy = FragmentStrictMode.Policy.Builder().apply {
			detectWrongFragmentContainer()
			detectFragmentTagUsage()
			detectRetainInstanceUsage()
			detectSetUserVisibleHint()
			detectWrongNestedHierarchy()
			detectFragmentReuse()
			penaltyLog()
		}.build()
	}

	private companion object {

		const val PREFS_DEBUG = "_debug"
		const val KEY_LEAK_CANARY = "leak_canary"

		fun getDebugPreferences(context: Context): SharedPreferences =
			context.getSharedPreferences(PREFS_DEBUG, MODE_PRIVATE)
	}
}
