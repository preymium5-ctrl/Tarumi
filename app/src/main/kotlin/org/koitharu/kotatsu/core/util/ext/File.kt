package org.koitharu.kotatsu.core.util.ext

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.fs.FileSequence
import org.koitharu.kotatsu.core.util.MimeTypes
import java.io.BufferedReader
import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.readAttributes
import kotlin.io.path.walk

fun File.subdir(name: String) = File(this, name).also {
	if (!it.exists()) it.mkdirs()
}

fun File.takeIfReadable() = takeIf { it.isReadable() }

fun File.takeIfWriteable() = takeIf { it.isWriteable() }

fun File.isNotEmpty() = length() != 0L

/**
 * Detect GIF / animated WebP / APNG / animated AVIF from file magic bytes.
 * Hitomi GameCG motion frames are often **animated AVIF** (`ftyp` brand `avis`).
 */
@Blocking
fun File.isAnimatedImageFile(): Boolean {
	if (!isFile || !canRead() || length() < 12L) return false
	// Name-based fast path (gif/apng/webm)
	if (name.isAnimatedImage()) return true
	return runCatching {
		inputStream().use { input ->
			val header = ByteArray(64)
			val n = input.read(header)
			if (n < 12) return@use false
			// GIF87a / GIF89a
			if (header[0] == 'G'.code.toByte() &&
				header[1] == 'I'.code.toByte() &&
				header[2] == 'F'.code.toByte()
			) {
				return@use true
			}
			// RIFF....WEBP
			val isWebp = header[0] == 'R'.code.toByte() &&
				header[1] == 'I'.code.toByte() &&
				header[2] == 'F'.code.toByte() &&
				header[3] == 'F'.code.toByte() &&
				header[8] == 'W'.code.toByte() &&
				header[9] == 'E'.code.toByte() &&
				header[10] == 'B'.code.toByte() &&
				header[11] == 'P'.code.toByte()
			if (isWebp) {
				val probe = ByteArray(minOf(length(), 256_000L).toInt())
				System.arraycopy(header, 0, probe, 0, n)
				val more = input.read(probe, n, probe.size - n).coerceAtLeast(0)
				return@use probe.containsAsciiChunk("ANIM", n + more) ||
					probe.containsAsciiChunk("ANMF", n + more)
			}
			// PNG signature → look for acTL (APNG)
			val isPng = header[0] == 0x89.toByte() &&
				header[1] == 'P'.code.toByte() &&
				header[2] == 'N'.code.toByte() &&
				header[3] == 'G'.code.toByte()
			if (isPng) {
				val probe = ByteArray(minOf(length(), 64_000L).toInt())
				System.arraycopy(header, 0, probe, 0, n)
				val more = input.read(probe, n, probe.size - n).coerceAtLeast(0)
				return@use probe.containsAsciiChunk("acTL", n + more)
			}
			// ISO BMFF (AVIF/HEIF): size(4) + "ftyp"(4) + major_brand(4) + …
			// Animated AVIF uses major/compatible brand "avis" (or "avifs").
			val isFtyp = n >= 12 &&
				header[4] == 'f'.code.toByte() &&
				header[5] == 't'.code.toByte() &&
				header[6] == 'y'.code.toByte() &&
				header[7] == 'p'.code.toByte()
			if (isFtyp) {
				val probe = ByteArray(minOf(length(), 8_192L).toInt())
				System.arraycopy(header, 0, probe, 0, n)
				val more = input.read(probe, n, probe.size - n).coerceAtLeast(0)
				val len = n + more
				// brands are 4-byte ASCII tokens after ftyp
				return@use probe.containsAsciiChunk("avis", len) ||
					probe.containsAsciiChunk("avifs", len)
			}
			false
		}
	}.getOrDefault(false)
}

private fun ByteArray.containsAsciiChunk(tag: String, length: Int): Boolean {
	if (tag.length != 4 || length < 4) return false
	val a = tag[0].code.toByte()
	val b = tag[1].code.toByte()
	val c = tag[2].code.toByte()
	val d = tag[3].code.toByte()
	val limit = length - 3
	for (i in 0 until limit) {
		if (this[i] == a && this[i + 1] == b && this[i + 2] == c && this[i + 3] == d) {
			return true
		}
	}
	return false
}

@Blocking
fun ZipFile.readText(entry: ZipEntry) = getInputStream(entry).use { output ->
	output.bufferedReader().use(BufferedReader::readText)
}

fun File.getStorageName(context: Context): String = runCatching {
	val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		manager.getStorageVolume(this)?.getDescription(context)?.let {
			return@runCatching it
		}
	}
	when {
		Environment.isExternalStorageEmulated(this) -> context.getString(R.string.internal_storage)
		Environment.isExternalStorageRemovable(this) -> context.getString(R.string.external_storage)
		else -> null
	}
}.getOrNull() ?: context.getString(R.string.other_storage)

fun Uri.toFileOrNull() = if (isFileUri()) path?.let(::File) else null

suspend fun File.deleteAwait() = runInterruptible(Dispatchers.IO) {
	delete() || deleteRecursively()
}

fun ContentResolver.resolveName(uri: Uri): String? {
	val fallback = uri.lastPathSegment
	if (uri.scheme != "content") {
		return fallback
	}
	query(uri, null, null, null, null)?.use {
		if (it.moveToFirst()) {
			it.getStringOrNull(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))?.let { name ->
				return name
			}
		}
	}
	return fallback
}

suspend fun File.computeSize(): Long = runInterruptible(Dispatchers.IO) {
	walkCompat(includeDirectories = false).sumOf { it.length() }
}

inline fun <R> File.withChildren(block: (children: Sequence<File>) -> R): R = FileSequence(this).use(block)

fun FileSequence(dir: File): FileSequence = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
	FileSequence.StreamImpl(dir)
} else {
	FileSequence.ListImpl(dir)
}

val File.creationTime
	get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		toPath().readAttributes<BasicFileAttributes>().creationTime().toMillis()
	} else {
		lastModified()
	}

@OptIn(ExperimentalPathApi::class)
fun File.walkCompat(includeDirectories: Boolean): Sequence<File> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
	// Use lazy loading on Android 8.0 and later
	val walk = if (includeDirectories) {
		toPath().walk(PathWalkOption.INCLUDE_DIRECTORIES)
	} else {
		toPath().walk()
	}
	walk.map { it.toFile() }
} else {
	// Directories are excluded by default in Path.walk(), so do it here as well
	val walk = walk()
	if (includeDirectories) walk else walk.filter { it.isFile }
}

val File.normalizedExtension: String?
	get() = MimeTypes.getNormalizedExtension(name)

fun File.isReadable() = runCatching {
	canRead()
}.getOrDefault(false)

fun File.isWriteable() = runCatching {
	canWrite()
}.getOrDefault(false)
