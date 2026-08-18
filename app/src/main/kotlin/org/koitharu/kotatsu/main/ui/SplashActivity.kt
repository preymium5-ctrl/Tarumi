package org.koitharu.kotatsu.main.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject

/**
 * Cold-start intro: plays the branded splash video (muted, ~3s), then [MainActivity].
 * System splash uses a transparent icon + first-frame backdrop so the static logo never appears.
 * Fully immersive: no Android status bar (or nav bar) over the open animation.
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
		hideSystemBarsForSplash()

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
		// Content view can recreate the controller target — re-apply immersive mode.
		hideSystemBarsForSplash()
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
		val parent = video.parent as? FrameLayout ?: return
		// Clip overflow; SurfaceView translations are unreliable — use FrameLayout gravity instead.
		parent.clipChildren = true
		parent.clipToPadding = true
		fun applyCenterCrop() {
			val pw = parent.width
			val ph = parent.height
			if (pw <= 0 || ph <= 0) return
			// Center-crop: scale to cover full screen, pin dead-center with Gravity.CENTER.
			val scale = maxOf(pw.toFloat() / vw, ph.toFloat() / vh)
			val scaledW = (vw * scale).toInt().coerceAtLeast(pw)
			val scaledH = (vh * scale).toInt().coerceAtLeast(ph)
			video.translationX = 0f
			video.translationY = 0f
			video.scaleX = 1f
			video.scaleY = 1f
			video.layoutParams = FrameLayout.LayoutParams(scaledW, scaledH, Gravity.CENTER)
			video.requestLayout()
		}
		parent.post { applyCenterCrop() }
		// One more pass after first layout/insets settle.
		parent.postDelayed({ applyCenterCrop() }, 32L)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus && !launched) {
			hideSystemBarsForSplash()
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

	/** Hide status (and nav) bars so the open video fills the screen edge-to-edge. */
	private fun hideSystemBarsForSplash() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		@Suppress("DEPRECATION")
		window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			window.attributes = window.attributes.apply {
				layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}
		}
		val controller = WindowCompat.getInsetsController(window, window.decorView)
		controller.isAppearanceLightStatusBars = false
		controller.isAppearanceLightNavigationBars = false
		controller.systemBarsBehavior =
			WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		// Status bar is the visible strip the user reported; hide all system bars for immersion.
		controller.hide(WindowInsetsCompat.Type.systemBars())
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
