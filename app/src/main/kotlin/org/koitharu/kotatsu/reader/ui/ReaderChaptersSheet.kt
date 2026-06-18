package org.koitharu.kotatsu.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import androidx.recyclerview.widget.GridLayoutManager
import org.koitharu.kotatsu.list.ui.GridSpanResolver
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.databinding.ItemPageThumbBinding
import org.koitharu.kotatsu.core.util.ext.setTextColorAttr
import com.google.android.material.R as materialR

class ReaderChaptersSheet : BottomSheetDialogFragment() {

    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_reader_chapters, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val buttonClose = view.findViewById<View>(R.id.buttonClose)
        buttonClose?.setOnClickListener { dismiss() }

        val textViewTitle = view.findViewById<TextView>(R.id.textViewTitle)
        val buttonTabList = view.findViewById<MaterialButton>(R.id.buttonTabList)
        val buttonTabGrid = view.findViewById<MaterialButton>(R.id.buttonTabGrid)

        val recyclerViewChapters = view.findViewById<RecyclerView>(R.id.recyclerViewChapters)
        recyclerViewChapters.layoutManager = LinearLayoutManager(requireContext())

        val recyclerViewPages = view.findViewById<RecyclerView>(R.id.recyclerViewPages)
        recyclerViewPages.layoutManager = GridLayoutManager(requireContext(), 3)
        val spanResolver = GridSpanResolver(resources)
        spanResolver.setGridSize(1.0f, recyclerViewPages)
        recyclerViewPages.addOnLayoutChangeListener(spanResolver)

        val details = viewModel.mangaDetails.value
        val currentState = viewModel.getCurrentState()
        val currentChapterId = currentState?.chapterId

        val chapters = details?.allChapters ?: emptyList()

        recyclerViewChapters.adapter = ChaptersAdapter(chapters, currentChapterId) { chapter ->
            viewModel.switchChapter(chapter.id, 0)
            dismiss()
        }
        
        // Scroll to the current chapter in the list
        val currentPosition = chapters.indexOfFirst { it.id == currentChapterId }
        if (currentPosition != -1) {
            recyclerViewChapters.scrollToPosition(currentPosition)
        }

        val pagesAdapter = PagesAdapter { page ->
            val callback = activity as? ReaderNavigationCallback
            if (callback != null && callback.onPageSelected(page)) {
                dismiss()
            }
        }
        recyclerViewPages.adapter = pagesAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { content ->
                pagesAdapter.updateData(content.pages, viewModel.getCurrentState())
            }
        }

        // Tabbing functions
        fun updateTabs(isListMode: Boolean) {
            if (isListMode) {
                recyclerViewChapters.visibility = View.VISIBLE
                recyclerViewPages.visibility = View.GONE
                textViewTitle.text = getString(R.string.chapters)

                buttonTabList.backgroundTintList = requireContext().getThemeColorStateList(materialR.attr.colorSurfaceVariant)
                buttonTabList.iconTint = requireContext().getThemeColorStateList(android.R.attr.textColorPrimary)

                buttonTabGrid.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                buttonTabGrid.iconTint = requireContext().getThemeColorStateList(android.R.attr.textColorSecondary)
            } else {
                recyclerViewChapters.visibility = View.GONE
                recyclerViewPages.visibility = View.VISIBLE
                textViewTitle.text = getString(R.string.pages)

                buttonTabList.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                buttonTabList.iconTint = requireContext().getThemeColorStateList(android.R.attr.textColorSecondary)

                buttonTabGrid.backgroundTintList = requireContext().getThemeColorStateList(materialR.attr.colorSurfaceVariant)
                buttonTabGrid.iconTint = requireContext().getThemeColorStateList(android.R.attr.textColorPrimary)
            }
        }

        buttonTabList.setOnClickListener { updateTabs(true) }
        buttonTabGrid.setOnClickListener { updateTabs(false) }

        // Start in List mode
        updateTabs(true)
    }

    private inner class ChaptersAdapter(
        private val list: List<MangaChapter>,
        private val currentChapterId: Long?,
        private val onClick: (MangaChapter) -> Unit
    ) : RecyclerView.Adapter<ChaptersAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val buttonItem: MaterialButton = view.findViewById(R.id.buttonChapterItem)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reader_chapter_sheet, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chapter = list[position]
            val title = chapter.getLocalizedTitle(resources, position + 1)
            holder.buttonItem.text = title

            val isCurrent = chapter.id == currentChapterId
            if (isCurrent) {
                holder.buttonItem.strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
                holder.buttonItem.setStrokeColorResource(R.color.taru_accent)
            } else {
                holder.buttonItem.strokeWidth = 0
            }

            holder.buttonItem.setOnClickListener {
                onClick(chapter)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    private inner class PagesAdapter(
        private var list: List<ReaderPage> = emptyList(),
        private var currentState: ReaderState? = null,
        private val onClick: (ReaderPage) -> Unit
    ) : RecyclerView.Adapter<PagesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemPageThumbBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPageThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val page = list[position]
            val isCurrent = page.chapterId == currentState?.chapterId && page.index == currentState?.page

            val gridWidth = holder.itemView.context.resources.getDimensionPixelSize(R.dimen.preferred_grid_width)
            holder.binding.imageViewThumb.exactImageSize = coil3.size.Size(
                width = gridWidth,
                height = (gridWidth / 13f * 18f).toInt(),
            )
            holder.binding.imageViewThumb.setImageAsync(page)

            with(holder.binding.textViewNumber) {
                setBackgroundResource(if (isCurrent) R.drawable.bg_badge_accent else R.drawable.bg_badge_empty)
                setTextColorAttr(if (isCurrent) materialR.attr.colorOnTertiary else android.R.attr.textColorPrimary)
                text = (page.index + 1).toString()
            }

            holder.itemView.setOnClickListener {
                onClick(page)
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateData(newList: List<ReaderPage>, newState: ReaderState?) {
            list = newList
            currentState = newState
            notifyDataSetChanged()
        }
    }
}
