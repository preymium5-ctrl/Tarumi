package org.koitharu.kotatsu.download.ui.list.chapters

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.drawableEnd
import org.koitharu.kotatsu.databinding.ItemChapterDownloadBinding

fun downloadChapterAD() = adapterDelegateViewBinding<DownloadChapter, DownloadChapter, ItemChapterDownloadBinding>(
	{ layoutInflater, parent -> ItemChapterDownloadBinding.inflate(layoutInflater, parent, false) },
) {

	val iconDone = ContextCompat.getDrawable(context, R.drawable.ic_check)

	bind {
		binding.textViewNumber.text = item.number
		binding.textViewTitle.text = item.name
		binding.textViewTitle.drawableEnd = if (item.isDownloaded) iconDone else null
		// Show a bar for the active chapter, any partial progress, and completed chapters.
		val showBar = item.isActive || item.progress > 0f || item.isDownloaded
		binding.progressBarChapter.isVisible = showBar
		if (showBar) {
			val isIndeterminate = item.isActive && item.progress <= 0f && !item.isDownloaded
			binding.progressBarChapter.isIndeterminate = isIndeterminate
			if (!isIndeterminate) {
				binding.progressBarChapter.max = 1000
				binding.progressBarChapter.setProgressCompat(
					(item.progress.coerceIn(0f, 1f) * 1000).toInt(),
					true,
				)
			}
		}
	}
}
