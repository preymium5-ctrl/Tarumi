package org.koitharu.kotatsu.core.network

import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsTracker @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	@BaseHttpClient private val okHttpClient: OkHttpClient
) {

	@WorkerThread
	fun trackAppSession() {
		val token = context.getString(R.string.mixpanel_project_token)
		if (token.isBlank() || token == "YOUR_MIXPANEL_PROJECT_TOKEN_HERE") {
			return
		}
		try {
			val distinctId = settings.analyticsInstallationId
			val payload = JSONArray().apply {
				put(
					JSONObject().apply {
						put("event", "App Session Start")
						put(
							"properties",
							JSONObject().apply {
								put("token", token)
								put("distinct_id", distinctId)
								put("app_version", BuildConfig.VERSION_NAME)
								put("\$os", "Android")
								put("\$os_version", Build.VERSION.RELEASE)
								put("\$model", Build.MODEL)
							}
						)
					}
				)
			}
			val body = payload.toString().toRequestBody("application/json".toMediaType())
			val request = Request.Builder()
				.url("https://api.mixpanel.com/track")
				.post(body)
				.build()
			okHttpClient.newCall(request).execute().close()
		} catch (e: Throwable) {
			e.printStackTraceDebug()
		}
	}
}
