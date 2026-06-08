package org.koitharu.kotatsu.home.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityContinueReadingBinding
import org.koitharu.kotatsu.databinding.ItemContinueReadingGridBinding
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.reader.ui.ReaderState

@AndroidEntryPoint
class ContinueReadingActivity : BaseActivity<ActivityContinueReadingBinding>() {

	private val viewModel: ContinueReadingViewModel by viewModels()
	private val adapter = ContinueReadingAdapter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityContinueReadingBinding.inflate(layoutInflater))
		setSupportActionBar(viewBinding.toolbar)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		title = getString(R.string.continue_reading)

		viewBinding.recyclerView.layoutManager = GridLayoutManager(this, COLUMNS)
		viewBinding.recyclerView.adapter = adapter
		viewModel.items.observe(this, adapter::submit)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
		viewBinding.recyclerView.updatePadding(
			left = bars.left + 28.dp(v),
			right = bars.right + 28.dp(v),
			bottom = bars.bottom + 24.dp(v),
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	private fun Int.dp(view: View): Int = (this * view.resources.displayMetrics.density).toInt()

	private companion object {
		const val COLUMNS = 3
	}
}

private class ContinueReadingAdapter : RecyclerView.Adapter<ContinueReadingAdapter.Holder>() {

	private val items = ArrayList<MangaWithHistory>()

	fun submit(newItems: List<MangaWithHistory>) {
		items.clear()
		items.addAll(newItems)
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		return Holder(
			ItemContinueReadingGridBinding.inflate(LayoutInflater.from(parent.context), parent, false),
		)
	}

	override fun getItemCount(): Int = items.size

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(items[position])
	}

	class Holder(
		private val binding: ItemContinueReadingGridBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(item: MangaWithHistory) {
			val manga = item.manga
			binding.textViewTitle.text = manga.title
			binding.textViewType.text = manga.detectComicType().label
			binding.textViewProgress.text = item.progressText()
			binding.textViewStatus.setText(R.string.status_reading)
			binding.imageViewCover.setImageAsync(
				manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl,
				manga,
			)
			binding.root.contentDescription = manga.title
			binding.root.setOnClickListener { view ->
				AppRouter.from(view)?.openReader(
					ReaderIntent.Builder(view.context)
						.mangaId(manga.id)
						.state(ReaderState(item.history))
						.build(),
					view,
				)
			}
		}

		private fun MangaWithHistory.progressText(): String {
			val total = history.chaptersCount.takeIf { it > 0 } ?: manga.chapters.orEmpty().size
			val chapters = manga.chapters.orEmpty()
			val current = chapters.findById(history.chapterId)?.let { chapter ->
				chapters.indexOfFirst { it.id == chapter.id }.takeIf { it >= 0 }?.plus(1)
			} ?: 0
			return if (total > 0 && current > 0) {
				"$current / $total"
			} else if (total > 0) {
				"- / $total"
			} else {
				"0 / 0"
			}
		}
	}
}
