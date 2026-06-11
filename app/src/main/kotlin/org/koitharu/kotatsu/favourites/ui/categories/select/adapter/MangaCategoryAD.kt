package org.koitharu.kotatsu.favourites.ui.categories.select.adapter

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import com.google.android.material.checkbox.MaterialCheckBox
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.databinding.ItemCategoryCheckableBinding
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel

fun mangaCategoryAD(
	clickListener: OnListItemClickListener<MangaCategoryItem>,
) = adapterDelegateViewBinding<MangaCategoryItem, ListModel, ItemCategoryCheckableBinding>(
	{ inflater, parent -> ItemCategoryCheckableBinding.inflate(inflater, parent, false) },
) {

	itemView.setOnClickListener {
		clickListener.onItemClick(item, itemView)
	}

	bind { payloads ->
		val isChecked = item.checkedState == MaterialCheckBox.STATE_CHECKED
		binding.checkBox.isChecked = isChecked
		val style = item.category.title.statusStyle(binding.root.context, isChecked)
		binding.root.background = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = 28f * binding.root.resources.displayMetrics.density
			setColor(style.background)
			setStroke((1f * binding.root.resources.displayMetrics.density).toInt(), style.stroke)
		}
		binding.textViewTitle.setTextColor(style.foreground)
		binding.imageViewIcon.imageTintList = ColorStateList.valueOf(style.foreground)
		binding.checkBox.buttonTintList = ColorStateList.valueOf(style.accent)
		if (ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED !in payloads) {
			binding.textViewTitle.text = item.category.title
			binding.imageViewIcon.setImageResource(item.category.title.statusIcon())
		}
	}
}

private fun String.statusIcon(): Int = when (trim().lowercase()) {
	"remove" -> R.drawable.ic_delete
	"reading" -> R.drawable.ic_read
	"completed" -> R.drawable.ic_check
	else -> R.drawable.ic_bookmark
}

private fun String.statusStyle(context: android.content.Context, isChecked: Boolean): FavoriteStatusStyle {
	if (!isChecked) {
		return FavoriteStatusStyle(
			background = context.getColor(R.color.taru_surface_button),
			stroke = context.getColor(R.color.taru_outline),
			foreground = context.getColor(R.color.taru_text_primary),
			accent = context.getColor(R.color.taru_accent)
		)
	}
	return when (trim().lowercase()) {
		"reading" -> FavoriteStatusStyle(
			background = 0xFFE7F1FF.toInt(),
			stroke = 0xFF74A7FF.toInt(),
			foreground = 0xFF0B56B3.toInt(),
			accent = 0xFF3B82F6.toInt(),
		)
		"planned" -> FavoriteStatusStyle(
			background = 0xFFF0ECFF.toInt(),
			stroke = 0xFF8D7BFF.toInt(),
			foreground = 0xFF5142C4.toInt(),
			accent = 0xFF6D5DF6.toInt(),
		)
		"completed" -> FavoriteStatusStyle(
			background = 0xFFEAF8EF.toInt(),
			stroke = 0xFF62BE7B.toInt(),
			foreground = 0xFF1F7A3F.toInt(),
			accent = 0xFF2EA557.toInt(),
		)
		"remove" -> FavoriteStatusStyle(
			background = 0xFFFFEEF0.toInt(),
			stroke = 0xFFFF9AA7.toInt(),
			foreground = 0xFFC92A3C.toInt(),
			accent = 0xFFE23A4D.toInt(),
		)
		else -> FavoriteStatusStyle(
			background = context.getColor(R.color.taru_surface_button),
			stroke = context.getColor(R.color.taru_outline),
			foreground = context.getColor(R.color.taru_text_primary),
			accent = context.getColor(R.color.taru_accent)
		)
	}
}

private data class FavoriteStatusStyle(
	val background: Int,
	val stroke: Int,
	val foreground: Int,
	val accent: Int,
)
