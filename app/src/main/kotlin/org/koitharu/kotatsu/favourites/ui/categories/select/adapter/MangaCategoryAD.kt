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
		val style = item.category.title.statusStyle(isChecked)
		binding.root.background = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = 18f * binding.root.resources.displayMetrics.density
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

private fun String.statusStyle(isChecked: Boolean): FavoriteStatusStyle = when (trim().lowercase()) {
	"reading" -> FavoriteStatusStyle(
		background = if (isChecked) 0xFFE7F1FF.toInt() else 0xFFF7FAFF.toInt(),
		stroke = if (isChecked) 0xFF74A7FF.toInt() else 0xFFD6E5FF.toInt(),
		foreground = if (isChecked) 0xFF0B56B3.toInt() else 0xFF385F93.toInt(),
		accent = 0xFF3B82F6.toInt(),
	)
	"planned" -> FavoriteStatusStyle(
		background = if (isChecked) 0xFFF0ECFF.toInt() else 0xFFFBFAFF.toInt(),
		stroke = if (isChecked) 0xFF8D7BFF.toInt() else 0xFFE4DEFF.toInt(),
		foreground = if (isChecked) 0xFF5142C4.toInt() else 0xFF615A8E.toInt(),
		accent = 0xFF6D5DF6.toInt(),
	)
	"completed" -> FavoriteStatusStyle(
		background = if (isChecked) 0xFFEAF8EF.toInt() else 0xFFF8FCF9.toInt(),
		stroke = if (isChecked) 0xFF62BE7B.toInt() else 0xFFD5EFDD.toInt(),
		foreground = if (isChecked) 0xFF1F7A3F.toInt() else 0xFF4D7259.toInt(),
		accent = 0xFF2EA557.toInt(),
	)
	"remove" -> FavoriteStatusStyle(
		background = if (isChecked) 0xFFFFEEF0.toInt() else 0xFFFFFAFA.toInt(),
		stroke = if (isChecked) 0xFFFF9AA7.toInt() else 0xFFFFDDE2.toInt(),
		foreground = if (isChecked) 0xFFC92A3C.toInt() else 0xFF8B5260.toInt(),
		accent = 0xFFE23A4D.toInt(),
	)
	else -> FavoriteStatusStyle(
		background = if (isChecked) 0xFFEAF1FF.toInt() else 0xFFFAFCFF.toInt(),
		stroke = if (isChecked) 0xFF8FB2FF.toInt() else 0xFFE2EAF5.toInt(),
		foreground = 0xFF101B2D.toInt(),
		accent = 0xFF7DA3FF.toInt(),
	)
}

private data class FavoriteStatusStyle(
	val background: Int,
	val stroke: Int,
	val foreground: Int,
	val accent: Int,
)
