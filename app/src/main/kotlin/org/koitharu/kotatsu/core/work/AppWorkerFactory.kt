package org.koitharu.kotatsu.core.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.local.ui.LocalStorageCleanupWorker
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.tracker.work.TrackWorker
import javax.inject.Inject

class AppWorkerFactory @Inject constructor(
	private val downloadWorkerFactory: DownloadWorker.Factory,
	private val localStorageCleanupWorkerFactory: LocalStorageCleanupWorker.Factory,
	private val suggestionsWorkerFactory: SuggestionsWorker.Factory,
	private val trackWorkerFactory: TrackWorker.Factory,
) : WorkerFactory() {

	override fun createWorker(
		appContext: Context,
		workerClassName: String,
		workerParameters: WorkerParameters,
	): ListenableWorker? = when (workerClassName) {
		DownloadWorker::class.java.name -> downloadWorkerFactory.create(appContext, workerParameters)
		LocalStorageCleanupWorker::class.java.name -> localStorageCleanupWorkerFactory.create(appContext, workerParameters)
		SuggestionsWorker::class.java.name -> suggestionsWorkerFactory.create(appContext, workerParameters)
		TrackWorker::class.java.name -> trackWorkerFactory.create(appContext, workerParameters)
		else -> null
	}
}
