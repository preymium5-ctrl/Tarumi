package org.koitharu.kotatsu.home.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Horizontal home recommendation rail — recycles cover views to cut RAM.
 */
class HomeRailAdapter(
	private val onClick: (Manga) -> Unit,
) : ListAdapter<Manga, HomeRailAdapter.Holder>(DiffCallback) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val view = LayoutInflater.from(parent.context)
			.inflate(R.layout.item_home_recommendation_cover, parent, false)
		val lp = RecyclerView.LayoutParams(
			(128 * parent.resources.displayMetrics.density).toInt(),
			RecyclerView.LayoutParams.WRAP_CONTENT,
		)
		view.layoutParams = lp
		return Holder(view as ViewGroup, onClick)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(getItem(position), position > 0)
	}

	class Holder(
		private val root: ViewGroup,
		private val onClick: (Manga) -> Unit,
	) : RecyclerView.ViewHolder(root) {

		private val cover: CoverImageView = root.findViewById(R.id.imageView_cover)
		private val title: TextView = root.findViewById(R.id.textView_title)
		private val type: TextView = root.findViewById(R.id.textView_type)

		fun bind(manga: Manga, hasLeadingMargin: Boolean) {
			(root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart =
				if (hasLeadingMargin) (14 * root.resources.displayMetrics.density).toInt() else 0
			// Prefer small cover URL; size request matches the 128dp rail tile.
			cover.setHomeCoverAsync(manga)
			title.text = manga.title
			type.text = manga.detectComicType().label
			root.contentDescription = manga.title
			root.setOnClickListener { onClick(manga) }
		}
	}

	private companion object DiffCallback : DiffUtil.ItemCallback<Manga>() {
		override fun areItemsTheSame(oldItem: Manga, newItem: Manga): Boolean = oldItem.id == newItem.id
		override fun areContentsTheSame(oldItem: Manga, newItem: Manga): Boolean =
			oldItem.title == newItem.title &&
				oldItem.coverUrl == newItem.coverUrl &&
				oldItem.largeCoverUrl == newItem.largeCoverUrl
	}
}
