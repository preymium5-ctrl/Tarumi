package org.koitharu.kotatsu.main.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject

/**
 * Cold-start intro: plays the branded splash video (muted, ~3s), then [MainActivity].
 * System splash uses a transparent icon + first-frame backdrop so the static logo never appears.
 */
@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

	@Inject
	lateinit var settings: AppSettings

	private var launched = false
	private var safetyRunnable: Runnable? = null
	private var videoView: VideoView? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		// Drop Android 12+ system splash immediately (no logo icon hold).
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			splashScreen.setOnExitAnimationListener { provider ->
				provider.remove()
			}
		}
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

		if (savedInstanceState != null) {
			goToMain()
			return
		}

		// Performance mode: skip the clip entirely.
		if (settings.isPerformanceMode) {
			goToMain()
			return
		}

		setContentView(R.layout.activity_splash)
		val video = findViewById<VideoView>(R.id.video_splash)
		val placeholder = findViewById<ImageView>(R.id.image_splash_placeholder)
		videoView = video

		val uri = Uri.parse("android.resource://$packageName/${R.raw.splash_open}")
		video.setVideoURI(uri)
		video.setOnPreparedListener { player ->
			// Always silent.
			player.isLooping = false
			try {
				player.setVolume(0f, 0f)
			} catch (_: Throwable) {
				// Some devices ignore volume on VideoView; track is already muted.
			}
			// Scale to cover the screen (center-crop style via layout + measure).
			scaleVideo(video, player)
			// Seek to start and start; hide placeholder on first frame.
			player.seekTo(0)
			player.setOnInfoListener { _, what, _ ->
				if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
					placeholder.isVisible = false
				}
				false
			}
			player.start()
			// Fallback hide if INFO never fires.
			placeholder.postDelayed({ placeholder.isVisible = false }, 120L)
		}
		video.setOnCompletionListener {
			goToMain()
		}
		video.setOnErrorListener { _, _, _ ->
			goToMain()
			true
		}

		// Hard stop if the player hangs (clip is 3s).
		safetyRunnable = Runnable {
			if (!launched) {
				video.stopPlayback()
				goToMain()
			}
		}
		window.decorView.postDelayed(safetyRunnable, SAFETY_MS)
	}

	private fun scaleVideo(video: VideoView, player: MediaPlayer) {
		val vw = player.videoWidth.takeIf { it > 0 } ?: return
		val vh = player.videoHeight.takeIf { it > 0 } ?: return
		val parent = video.parent as? View ?: return
		parent.post {
			val pw = parent.width.toFloat()
			val ph = parent.height.toFloat()
			if (pw <= 0f || ph <= 0f) return@post
			val scale = maxOf(pw / vw, ph / vh)
			val lp = video.layoutParams
			lp.width = (vw * scale).toInt()
			lp.height = (vh * scale).toInt()
			video.layoutParams = lp
		}
	}

	override fun onDestroy() {
		safetyRunnable?.let { window.decorView.removeCallbacks(it) }
		safetyRunnable = null
		try {
			videoView?.stopPlayback()
		} catch (_: Throwable) {
		}
		videoView = null
		super.onDestroy()
	}

	private fun goToMain() {
		if (launched || isFinishing) return
		launched = true
		try {
			videoView?.stopPlayback()
		} catch (_: Throwable) {
		}
		val next = Intent(this, MainActivity::class.java).apply {
			action = intent.action
			data = intent.data
			putExtras(intent)
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		}
		startActivity(next)
		@Suppress("DEPRECATION")
		overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
		finish()
	}

	private companion object {
		const val SAFETY_MS = 3500L
	}
}
