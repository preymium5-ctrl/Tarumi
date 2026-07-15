package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemDownloadBinding
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.download.ui.list.chapters.downloadChapterAD
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.format

fun downloadItemAD(
	lifecycleOwner: LifecycleOwner,
	listener: DownloadItemListener,
) = adapterDelegateViewBinding<DownloadItemModel, ListModel, ItemDownloadBinding>(
	{ inflater, parent -> ItemDownloadBinding.inflate(inflater, parent, false) },
) {

	val percentPattern = context.resources.getString(R.string.percent_string_pattern)
	var chaptersJob: Job? = null

	val clickListener = object : View.OnClickListener, View.OnLongClickListener {
		override fun onClick(v: View) {
			when (v.id) {
				R.id.button_cancel -> listener.onCancelClick(item)
				R.id.button_resume -> listener.onResumeClick(item)
				R.id.button_skip -> listener.onSkipClick(item)
				R.id.button_skip_all -> listener.onSkipAllClick(item)
				R.id.button_pause -> listener.onPauseClick(item)
				R.id.button_expand -> listener.onExpandClick(item)
				else -> listener.onItemClick(item, v)
			}
		}

		override fun onLongClick(v: View): Boolean {
			return listener.onItemLongClick(item, v)
		}
	}
	val chaptersAdapter = BaseListAdapter<DownloadChapter>()
		.addDelegate(ListItemType.CHAPTER_LIST, downloadChapterAD())

	binding.recyclerViewChapters.adapter = chaptersAdapter
	binding.buttonCancel.setOnClickListener(clickListener)
	binding.buttonPause.setOnClickListener(clickListener)
	binding.buttonResume.setOnClickListener(clickListener)
	binding.buttonSkip.setOnClickListener(clickListener)
	binding.buttonSkipAll.setOnClickListener(clickListener)
	binding.buttonExpand.setOnClickListener(clickListener)
	itemView.setOnClickListener(clickListener)
	itemView.setOnLongClickListener(clickListener)

	fun scrollToCurrentChapter() {
		val rv = binding.recyclerViewChapters
		if (!rv.isVisible) {
			return
		}
		val chapters = chaptersAdapter.items
		if (chapters.isEmpty()) {
			return
		}
		val targetPos = item.chaptersDownloaded.coerceIn(chapters.indices)
		(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(targetPos, rv.height / 3)
	}

	bind { payloads ->
		binding.textViewTitle.text = item.manga?.title ?: getString(R.string.unknown)
		binding.imageViewCover.setImageAsync(item.manga?.coverUrl, item.manga)
		// Re-subscribe on full bind and on progress/status payloads so per-chapter
		// progress bars update while a download is running (online + offline).
		val shouldResubscribeChapters = chaptersJob == null ||
			payloads.isEmpty() ||
			ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED in payloads ||
			ListModelDiffCallback.PAYLOAD_PROGRESS_CHANGED in payloads
		if (shouldResubscribeChapters) {
			chaptersJob?.cancel()
			chaptersJob = lifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
				item.chapters.collect { chapters ->
					binding.buttonExpand.isGone = chapters.isNullOrEmpty()
					chaptersAdapter.emit(chapters)
					scrollToCurrentChapter()
				}
			}
		} else if (ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED in payloads) {
			binding.recyclerViewChapters.post {
				scrollToCurrentChapter()
			}
		}
		binding.buttonExpand.isChecked = item.isExpanded
		binding.buttonExpand.setContentDescriptionAndTooltip(if (item.isExpanded) R.string.collapse else R.string.expand)
		binding.recyclerViewChapters.isVisible = item.isExpanded
		when (item.workState) {
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.BLOCKED -> {
				binding.textViewStatus.setText(R.string.queued)
				binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(STATE_QUEUED)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.isVisible = false
				binding.buttonCancel.isVisible = true
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.RUNNING -> {
				binding.textViewStatus.setText(
					if (item.isPaused) R.string.paused else R.string.manga_downloading_,
				)
				binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(
					if (item.isPaused) STATE_PAUSED else STATE_ACTIVE,
				)
				binding.progressBar.isIndeterminate = item.isIndeterminate
				binding.progressBar.isVisible = true
				binding.progressBar.max = item.max
				binding.progressBar.isEnabled = !item.isPaused
				binding.progressBar.setProgressCompat(item.progress, payloads.isNotEmpty())
				binding.textViewPercent.text = percentPattern.format((item.percent * 100f).format(1))
				binding.textViewPercent.isVisible = true
				binding.textViewDetails.textAndVisible = when {
					item.isPaused -> item.getErrorMessage(context)
					item.isStuck -> context.getString(R.string.stuck)
					else -> item.getEtaString()
				}
				binding.buttonCancel.isVisible = true
				binding.buttonResume.isVisible = item.isPaused
				binding.buttonResume.text = ""
				binding.buttonResume.setContentDescriptionAndTooltip(if (item.error == null) R.string.resume else R.string.retry)
				binding.buttonSkip.isVisible = item.isPaused && item.error != null
				binding.buttonSkipAll.isVisible = item.isPaused && item.error != null
				binding.buttonPause.isVisible = item.canPause
			}

			WorkInfo.State.SUCCEEDED -> {
				binding.textViewStatus.setText(R.string.download_complete)
				binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(STATE_DONE)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				if (item.chaptersDownloaded > 0) {
					binding.textViewDetails.text = context.resources.getQuantityStringSafe(
						R.plurals.chapters,
						item.chaptersDownloaded,
						item.chaptersDownloaded,
					)
					binding.textViewDetails.isVisible = true
				} else {
					binding.textViewDetails.isVisible = false
				}
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.FAILED -> {
				binding.textViewStatus.setText(R.string.error_occurred)
				binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(STATE_ERROR)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.textAndVisible = item.getErrorMessage(context)
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.CANCELLED -> {
				binding.textViewStatus.setText(R.string.canceled)
				binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(STATE_CANCELLED)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.isVisible = false
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}
		}
	}
}

private val STATE_ACTIVE = Color.parseColor("#3D7DDB")
private val STATE_PAUSED = Color.parseColor("#7B6BC5")
private val STATE_QUEUED = Color.parseColor("#68758A")
private val STATE_DONE = Color.parseColor("#16854C")
private val STATE_ERROR = Color.parseColor("#D32F2F")
private val STATE_CANCELLED = Color.parseColor("#8B96A8")
