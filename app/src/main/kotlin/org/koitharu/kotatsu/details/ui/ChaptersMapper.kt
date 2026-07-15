package org.koitharu.kotatsu.details.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.toListItem
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.mapToSet

fun MangaDetails.mapChapters(
	currentChapterId: Long,
	currentChapterProgress: Float,
	newCount: Int,
	branch: String?,
	bookmarks: List<Bookmark>,
	isGrid: Boolean,
	isDownloadedOnly: Boolean,
): List<ChapterListItem> {
	val remoteChapters = if (branch == null) {
		allChapters
	} else {
		chapters[branch].orEmpty()
	}
	val localChapters = if (branch == null) {
		local?.manga?.chapters.orEmpty()
	} else {
		local?.manga?.getChapters(branch).orEmpty()
	}
	if (remoteChapters.isEmpty() && localChapters.isEmpty()) {
		return emptyList()
	}
	val bookmarked = bookmarks.mapToSet { it.chapterId }
	val newFrom = if (newCount == 0 || remoteChapters.isEmpty()) Int.MAX_VALUE else remoteChapters.size - newCount
	val ids = buildSet(maxOf(remoteChapters.size, localChapters.size)) {
		remoteChapters.mapTo(this) { it.id }
		localChapters.mapTo(this) { it.id }
	}
	val result = ArrayList<ChapterListItem>(ids.size)
	// Index local chapters by id and url so offline downloads still match after remaps.
	val localById = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
	} else {
		null
	}
	val localByUrl = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.url }
	} else {
		null
	}
	fun takeLocal(chapter: org.koitharu.kotatsu.parsers.model.MangaChapter): org.koitharu.kotatsu.parsers.model.MangaChapter? {
		localById?.remove(chapter.id)?.let { matched ->
			localByUrl?.remove(matched.url)
			return matched
		}
		localByUrl?.remove(chapter.url)?.let { matched ->
			localById?.remove(matched.id)
			return matched
		}
		return null
	}
	fun isCurrentChapter(chapterId: Long): Boolean = chapterId == currentChapterId
	var isUnread = currentChapterId !in ids &&
		localChapters.none { it.id == currentChapterId || it.url == remoteChapters.find { r -> r.id == currentChapterId }?.url }
	if (!isDownloadedOnly || local?.manga?.chapters == null) {
		for (chapter in remoteChapters) {
			val local = takeLocal(chapter)
			val display = local ?: chapter
			val current = isCurrentChapter(display.id) || isCurrentChapter(chapter.id)
			if (current) {
				isUnread = true
			}
			result += display.toListItem(
				isCurrent = current,
				isUnread = isUnread,
				isNew = isUnread && result.size >= newFrom,
				isDownloaded = local != null,
				isBookmarked = chapter.id in bookmarked || display.id in bookmarked,
				isGrid = isGrid,
				progressPercent = if (current) currentChapterProgress else -1f,
			)
		}
	}
	if (!localById.isNullOrEmpty()) {
		// Keep leftover local-only chapters in reading order (by number), not arbitrary map order.
		// Appending unsorted leftovers + reverse made "current" jump to the bottom and scrambled lists.
		val leftovers = localById.values.sortedWith(
			compareBy<org.koitharu.kotatsu.parsers.model.MangaChapter> { it.number }
				.thenBy { it.title.orEmpty() },
		)
		for (chapter in leftovers) {
			val current = isCurrentChapter(chapter.id)
			if (current) {
				isUnread = true
			}
			result += chapter.toListItem(
				isCurrent = current,
				isUnread = isUnread,
				isNew = false,
				isDownloaded = !isLocal,
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
				progressPercent = if (current) currentChapterProgress else -1f,
			)
		}
	}
	return result
}

fun List<ChapterListItem>.withVolumeHeaders(context: Context): MutableList<ListModel> {
	var prevVolume = 0
	val result = ArrayList<ListModel>((size * 1.4).toInt())
	for (item in this) {
		val chapter = item.chapter
		if (chapter.volume != prevVolume) {
			val text = if (chapter.volume == 0) {
				context.getString(R.string.volume_unknown)
			} else {
				context.getString(R.string.volume_, chapter.volume)
			}
			result.add(ListHeader(text))
			prevVolume = chapter.volume
		}
		result.add(item)
	}
	return result
}
