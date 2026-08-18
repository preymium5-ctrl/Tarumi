package org.koitharu.kotatsu.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocalizedTitle

class ReaderDownloadSheet : BottomSheetDialogFragment() {

    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_reader_download, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val buttonClose = view.findViewById<View>(R.id.buttonClose)
        buttonClose?.setOnClickListener { dismiss() }

        val optionCurrentChapter = view.findViewById<View>(R.id.optionCurrentChapter)
        val optionWholeManga = view.findViewById<View>(R.id.optionWholeManga)

        val textCurrentChapterName = view.findViewById<TextView>(R.id.textCurrentChapterName)
        val textTotalChapters = view.findViewById<TextView>(R.id.textTotalChapters)

        val uiState = viewModel.uiState.value
        val currentChapter = uiState?.chapter
        val chapterIndex = uiState?.chapterIndex ?: -1
        val allChapters = viewModel.mangaDetails.value?.allChapters ?: emptyList()
        val totalChapters = allChapters.size

        if (currentChapter != null) {
            val title = currentChapter.getLocalizedTitle(resources, chapterIndex + 1)
            textCurrentChapterName.text = title
        } else {
            textCurrentChapterName.text = ""
        }

        textTotalChapters.text = resources.getQuantityString(R.plurals.chapters, totalChapters, totalChapters)

        optionCurrentChapter.setOnClickListener {
            viewModel.downloadCurrentChapter()
            dismiss()
        }

        optionWholeManga.setOnClickListener {
            viewModel.downloadAllChapters()
            dismiss()
        }
    }
}
