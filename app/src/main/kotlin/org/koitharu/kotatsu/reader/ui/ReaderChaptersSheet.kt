package org.koitharu.kotatsu.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.parsers.model.MangaChapter

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

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewChapters)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val details = viewModel.mangaDetails.value
        val currentChapterId = viewModel.getCurrentState()?.chapterId

        val chapters = details?.allChapters ?: emptyList()

        recyclerView.adapter = ChaptersAdapter(chapters, currentChapterId) { chapter ->
            viewModel.switchChapter(chapter.id, 0)
            dismiss()
        }
        
        // Scroll to the current chapter in the list
        val currentPosition = chapters.indexOfFirst { it.id == currentChapterId }
        if (currentPosition != -1) {
            recyclerView.scrollToPosition(currentPosition)
        }
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
}
