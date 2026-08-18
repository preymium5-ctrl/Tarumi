package org.koitharu.kotatsu.download.ui.list.chapters

import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel

data class DownloadChapter(
	val number: String?,
	val name: String,
	val isDownloaded: Boolean,
	/** 0f..1f for the chapter being downloaded; 1f when done; 0f when waiting. */
	val progress: Float = if (isDownloaded) 1f else 0f,
	val isActive: Boolean = false,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is DownloadChapter && other.name == name
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		return if (previousState is DownloadChapter &&
			previousState.name == name &&
			previousState.number == number
		) {
			ListModelDiffCallback.PAYLOAD_PROGRESS_CHANGED
		} else {
			super.getChangePayload(previousState)
		}
	}
}
