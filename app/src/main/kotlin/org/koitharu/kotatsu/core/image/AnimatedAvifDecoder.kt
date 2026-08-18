package org.koitharu.kotatsu.core.image

import android.os.Build
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Routes **animated AVIF** (ISO BMFF brand `avis`) to Coil's [AnimatedImageDecoder]
 * (Android [android.graphics.ImageDecoder]).
 *
 * Coil's stock [AnimatedImageDecoder.Factory] only treats HEIF brands
 * `msf1` / `hevc` / `hevx` as animated — Hitomi GameCG motion frames use major
 * brand **`avis`**, so they were falling through to [AvifImageDecoder] which
 * only paints frame 1 (no movement).
 */
object AnimatedAvifDecoder {

	class Factory(
		private val enforceMinimumFrameDelay: Boolean = true,
	) : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader,
		): Decoder? {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
			if (!isAnimatedAvif(result.source.source())) return null
			return AnimatedImageDecoder(result.source, options, enforceMinimumFrameDelay)
		}

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()
	}

	/**
	 * True when the buffer is an animated AVIF sequence (`ftyp` major or
	 * compatible brand `avis`).
	 */
	fun isAnimatedAvif(source: BufferedSource): Boolean {
		return runCatching {
			// Need at least size + "ftyp" + major brand
			if (!source.request(16L)) return false
			if (!source.rangeEquals(4L, FTYP)) return false
			// major brand at offset 8
			if (source.rangeEquals(8L, AVIS)) {
				return true
			}
			// Scan compatible brands (from offset 16, 4 bytes each) within first 64 bytes
			val limit = if (source.request(64L)) minOf(source.buffer.size, 64L) else source.buffer.size
			var off = 16L
			while (off + 4L <= limit) {
				if (source.rangeEquals(off, AVIS)) {
					return true
				}
				off += 4L
			}
			// Some files put avis later in a larger ftyp — peek a bit more
			if (source.request(256L)) {
				val bytes = source.peek().readByteArray(minOf(256L, source.buffer.size))
				val ascii = bytes.toString(Charsets.ISO_8859_1)
				return ascii.contains("avis")
			}
			false
		}.getOrDefault(false)
	}

	private val FTYP: ByteString = "ftyp".encodeUtf8()
	private val AVIS: ByteString = "avis".encodeUtf8()
}
