package org.koitharu.kotatsu.reader.ui

import android.app.assist.AssistContent
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.DialogErrorObserver
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.content.ContentValues
import android.provider.MediaStore
import kotlin.math.roundToInt
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.findCloudFlareException
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setCheckbox
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.IdlingDetector
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.hasGlobalPoint
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.postDelayed
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.core.util.ext.zipWithPrevious
import org.koitharu.kotatsu.databinding.ActivityReaderBinding
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet
import org.koitharu.kotatsu.details.ui.pager.pages.PagesSavedObserver
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.config.ReaderConfigSheet
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.reader.ui.tapgrid.TapGridDispatcher
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ReaderActivity :
    BaseFullscreenActivity<ActivityReaderBinding>(),
    TapGridDispatcher.OnGridTouchListener,
    ReaderConfigSheet.Callback,
    ReaderControlDelegate.OnInteractionListener,
    ReaderNavigationCallback,
    IdlingDetector.Callback,
    ZoomControl.ZoomControlListener,
    View.OnClickListener,
    ScrollTimerControlView.OnVisibilityChangeListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var tapGridSettings: TapGridSettings

    @Inject
    lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

    @Inject
    lateinit var scrollTimerFactory: ScrollTimer.Factory

    @Inject
    lateinit var screenOrientationHelper: ScreenOrientationHelper

    private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)

    private val viewModel: ReaderViewModel by viewModels()

    override val readerMode: ReaderMode?
        get() = readerManager.currentMode

    private lateinit var scrollTimer: ScrollTimer
    private lateinit var pageSaveHelper: PageSaveHelper
    private lateinit var touchHelper: TapGridDispatcher
    private lateinit var controlDelegate: ReaderControlDelegate
    private var gestureInsets: Insets = Insets.NONE
    private lateinit var readerManager: ReaderManager
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }

    // Tracks whether the foldable device is in an unfolded state (half-opened or flat)
    private var isFoldUnfolded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityReaderBinding.inflate(layoutInflater))
        readerManager = ReaderManager(supportFragmentManager, viewBinding.container, settings)
        setDisplayHomeAsUp(isEnabled = false, showUpAsClose = false)
        hideReaderNavigationBar()
        touchHelper = TapGridDispatcher(viewBinding.root, this)
        scrollTimer = scrollTimerFactory.create(resources, this, this)
        pageSaveHelper = pageSaveHelperFactory.create(this)
        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        viewBinding.zoomControl.listener = this
        viewBinding.actionsView.listener = this
        viewBinding.buttonTimer?.setOnClickListener(this)
        viewBinding.buttonReaderTopBack?.setOnClickListener(this)
        viewBinding.buttonReaderTopSettings?.setOnClickListener(this)
        viewBinding.buttonEndPrevChapter?.setOnClickListener(this)
        viewBinding.buttonEndChapters?.setOnClickListener(this)
        viewBinding.buttonEndNextChapter?.setOnClickListener(this)

        viewBinding.customButtonBack?.setOnClickListener(this)
        viewBinding.customButtonHome?.setOnClickListener(this)
        viewBinding.customButtonDownload?.setOnClickListener(this)
        viewBinding.customButtonRefresh?.setOnClickListener(this)
        viewBinding.customButtonScreenshot?.setOnClickListener(this)
        viewBinding.customButtonPlay?.setOnClickListener(this)
        viewBinding.customButtonPrevChapter?.setOnClickListener(this)
        viewBinding.customButtonNextChapter?.setOnClickListener(this)
        viewBinding.customButtonScrollToTop?.setOnClickListener(this)
        viewBinding.customButtonSettings?.setOnClickListener(this)
        viewBinding.customReaderBottomProgressCapsule?.setOnClickListener(this)

        var isCustomSliderChanged = false
        var isCustomSliderTracking = false
        viewBinding.customSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isCustomSliderTracking = true
                isCustomSliderChanged = false
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isCustomSliderTracking = false
                if (isCustomSliderChanged) {
                    switchPageTo(slider.value.toInt())
                }
            }
        })
        viewBinding.customSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                if (isCustomSliderTracking) {
                    isCustomSliderChanged = true
                } else {
                    switchPageTo(value.toInt())
                }
            }
        }

        idlingDetector.bindToLifecycle(this)
        screenOrientationHelper.applySettings()
        viewModel.isBookmarkAdded.observe(this) { viewBinding.actionsView.isBookmarkAdded = it }
        scrollTimer.isActive.observe(this) { active ->
            updateScrollTimerButton()
            viewBinding.actionsView.setTimerActive(active)
            if (active) {
                viewBinding.customButtonPlay?.setIconResource(R.drawable.ic_action_pause)
            } else {
                viewBinding.customButtonPlay?.setIconResource(R.drawable.ic_play)
            }
        }
        viewBinding.timerControl.onVisibilityChangeListener = this
        viewBinding.timerControl.attach(scrollTimer, this)
        if (resources.getBoolean(R.bool.is_tablet)) {
            viewBinding.timerControl.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                topMargin = marginEnd + getThemeDimensionPixelOffset(appcompatR.attr.actionBarSize)
            }
        }

        val loadingErrorDialog = DialogErrorObserver(
            host = viewBinding.container,
            fragment = null,
            resolver = exceptionResolver,
            onResolved = { isResolved ->
                if (isResolved) {
                    viewModel.reload()
                } else if (viewModel.content.value.pages.isEmpty()) {
                    dispatchNavigateUp()
                }
            },
        )
        viewModel.onLoadingError.observeEvent(this) { error ->
            // Chapter / page load is an explicit user action ("opening a chapter to read"), so auto-resolve
            // is appropriate here. If the per-source auto-solve toggle is on (or it's not a CF error),
            // fall back to the standard dialog observer with the manual "Solve" button.
            val cf = error.findCloudFlareException()
            val source = cf?.source
            val autoDisabled = source != null && SourceSettings(this@ReaderActivity, source).isCaptchaAutoResolveDisabled
            if (cf is CloudFlareProtectedException && !autoDisabled) {
                val resolved = exceptionResolver.resolve(cf, tryAutoResolve = true)
                if (resolved) {
                    viewModel.reload()
                } else {
                    // Auto-resolve failed — fall back to the manual "Solve" dialog so the user can retry.
                    loadingErrorDialog.emit(error)
                }
            } else {
                loadingErrorDialog.emit(error)
            }
        }
        val errorSnackbar = SnackbarErrorObserver(
            host = viewBinding.container,
            fragment = null,
            resolver = exceptionResolver,
            onResolved = null,
        )
        viewModel.onError.observeEvent(this) { error ->
            // Same auto-resolve treatment as onLoadingError: errors fired *after* the manga is already
            // loaded (chapter changes mid-reading, prefetch failures, page download failures) should
            // auto-resolve too if the per-source toggle allows it — otherwise the user gets a snackbar
            // with the manual "Solve" action instead.
            val cf = error.findCloudFlareException()
            val source = cf?.source
            val autoDisabled = source != null &&
                SourceSettings(this@ReaderActivity, source).isCaptchaAutoResolveDisabled
            if (cf is CloudFlareProtectedException && !autoDisabled) {
                val resolved = exceptionResolver.resolve(cf, tryAutoResolve = true)
                if (resolved) {
                    viewModel.reload()
                } else {
                    errorSnackbar.emit(error)
                }
            } else {
                errorSnackbar.emit(error)
            }
        }
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(viewBinding.container))
        viewModel.onDownloadStarted.observeEvent(this) {
            val anchor = if (viewBinding.customReaderBottomProgressLayout?.isVisible == true) {
                viewBinding.customReaderBottomProgressLayout
            } else {
                null
            }
            Snackbar.make(viewBinding.container, R.string.download_started, Snackbar.LENGTH_SHORT)
                .setAnchorView(anchor)
                .show()
        }
        viewModel.onDownloadFinished.observeEvent(this) {
            val anchor = if (viewBinding.customReaderBottomProgressLayout?.isVisible == true) {
                viewBinding.customReaderBottomProgressLayout
            } else {
                null
            }
            Snackbar.make(viewBinding.container, R.string.download_finished, Snackbar.LENGTH_SHORT)
                .setAnchorView(anchor)
                .show()
        }
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        combine(
            viewModel.isLoading,
            viewModel.content.map { it.pages.isNotEmpty() }.distinctUntilChanged(),
            ::Pair,
        ).flowOn(Dispatchers.Default)
            .observe(this, this::onLoadingStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarTransparent.observe(this) { viewBinding.infoBar.drawBackground = !it }
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            val anchor = if (viewBinding.customReaderBottomProgressLayout?.isVisible == true) {
                viewBinding.customReaderBottomProgressLayout
            } else {
                null
            }
            Snackbar.make(viewBinding.container, msgId, Snackbar.LENGTH_SHORT)
                .setAnchorView(anchor)
                .show()
        }
        viewModel.readerSettingsProducer.observe(this) {
            viewBinding.infoBar.applyColorScheme(isBlackOnWhite = it.background.isLight(this))
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            viewBinding.zoomControl.isVisible = it
        }
        addMenuProvider(ReaderMenuProvider(viewModel))

        observeWindowLayout()

        // Apply initial double-mode considering foldable setting
        applyDoubleModeAuto()
        updateCustomScrollAdvance()
        updateScreenshotButtonVisibility()
    }

    override fun getParentActivityIntent(): Intent? {
        if (intent.getBooleanExtra(ReaderIntent.EXTRA_BROWSER_MODE, false)) {
            return null
        }
        val manga = viewModel.getMangaOrNull() ?: return null
        return AppRouter.detailsIntent(this, manga)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!viewBinding.timerControl.isVisible) {
            scrollTimer.onUserInteraction()
        }
        idlingDetector.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.onPause()
    }

    override fun onStart() {
        super.onStart()
        settings.subscribe(this)
    }

    override fun onStop() {
        settings.unsubscribe(this)
        super.onStop()
        viewModel.onStop()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
    }

    override fun isNsfwContent(): Flow<Boolean> = viewModel.isMangaNsfw

    override fun onIdle() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.onIdle()
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        updateScrollTimerButton()
    }

    override fun onZoomIn() {
        readerManager.currentReader?.onZoomIn()
    }

    override fun onZoomOut() {
        readerManager.currentReader?.onZoomOut()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_timer -> onScrollTimerClick(isLongClick = false)
            R.id.button_reader_top_back -> dispatchNavigateUp()
            R.id.button_reader_top_settings -> openMenu()
            R.id.buttonEndPrevChapter -> switchChapterBy(-1)
            R.id.buttonEndChapters -> router.showChapterPagesSheet(ChaptersPagesSheet.TAB_CHAPTERS)
            R.id.buttonEndNextChapter -> switchChapterBy(1)
            R.id.custom_button_back -> dispatchNavigateUp()
            R.id.custom_button_home -> {
                val intent = AppRouter.homeIntent(this)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
            }
            R.id.custom_button_download -> showDownloadOptionsDialog()
            R.id.custom_button_refresh -> refreshCurrentChapter()
            R.id.custom_button_screenshot -> toggleLongScreenshotSession()
            R.id.customButtonPlay -> scrollTimer.setActive(!scrollTimer.isActive.value)
            R.id.custom_button_prev_chapter -> switchChapterBy(-1)
            R.id.custom_button_next_chapter -> switchChapterBy(1)
            R.id.custom_button_scroll_to_top -> switchPageTo(0)
            R.id.custom_button_settings -> openMenu()
            R.id.custom_reader_bottom_progress_capsule -> {
                ReaderChaptersSheet().show(supportFragmentManager, "ReaderChaptersSheet")
            }
        }
    }

    private fun onInitReader(mode: ReaderMode?) {
        if (mode == null) {
            return
        }
        if (readerManager.currentMode != mode) {
            readerManager.replace(mode)
        }
        if (viewBinding.appbarTop.isVisible) {
            lifecycle.postDelayed(TimeUnit.SECONDS.toMillis(1), hideUiRunnable)
        }
        viewBinding.actionsView.setSliderReversed(mode == ReaderMode.REVERSED)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    private fun onLoadingStateChanged(value: Pair<Boolean, Boolean>) {
        val (isLoading, hasPages) = value
        val showLoadingLayout = isLoading && !hasPages
        if (viewBinding.layoutLoading.isVisible != showLoadingLayout) {
            val transition = Fade().addTarget(viewBinding.layoutLoading)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            viewBinding.layoutLoading.isVisible = showLoadingLayout
        }
        if (isLoading && hasPages) {
            viewBinding.toastView.show(R.string.loading_)
        } else {
            viewBinding.toastView.hide()
        }
        invalidateOptionsMenu()
    }

    override fun onGridTouch(area: TapGridArea): Boolean {
        return isReaderResumed() && controlDelegate.onGridTouch(area)
    }

    override fun onGridLongTouch(area: TapGridArea) {
        if (isReaderResumed()) {
            controlDelegate.onGridLongTouch(area)
        }
    }

    override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
        return if (
            rawX <= gestureInsets.left ||
            rawY <= gestureInsets.top ||
            rawX >= viewBinding.root.width - gestureInsets.right ||
            rawY >= viewBinding.root.height - gestureInsets.bottom ||
            viewBinding.appbarTop.hasGlobalPoint(rawX, rawY) ||
            viewBinding.toolbarDocked?.hasGlobalPoint(rawX, rawY) == true
        ) {
            false
        } else {
            val touchables = window.peekDecorView()?.touchables
            touchables?.none { it.hasGlobalPoint(rawX, rawY) } != false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchHelper.dispatchTouchEvent(ev)
        if (!viewBinding.timerControl.hasGlobalPoint(ev.rawX.toInt(), ev.rawY.toInt())) {
            scrollTimer.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onChapterSelected(chapter: MangaChapter): Boolean {
        viewModel.switchChapter(chapter.id, 0)
        return true
    }

    override fun onPageSelected(page: ReaderPage): Boolean {
        lifecycleScope.launch(Dispatchers.Default) {
            val pages = viewModel.content.value.pages
            val index = pages.indexOfFirst { it.chapterId == page.chapterId && it.id == page.id }
            if (index != -1) {
                withContext(Dispatchers.Main) {
                    readerManager.currentReader?.switchPageTo(index, true)
                }
            } else {
                viewModel.switchChapter(page.chapterId, page.index)
            }
        }
        return true
    }

    override fun onReaderModeChanged(mode: ReaderMode) {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.switchMode(mode)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    override fun onDoubleModeChanged(isEnabled: Boolean) {
        // Combine manual toggle with foldable auto setting
        applyDoubleModeAuto(isEnabled)
    }

    private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Auto double-page on foldable when device is unfolded (half-opened or flat)
        val autoFoldable = settings.isReaderDoubleOnFoldable && isFoldUnfolded
        val manualLandscape = (manualEnabled ?: settings.isReaderDoubleOnLandscape) && isLandscape
        val autoEnabled = autoFoldable || manualLandscape
        readerManager.setDoubleReaderMode(autoEnabled)
    }

    private fun setKeepScreenOn(isKeep: Boolean) {
        if (isKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setUiIsVisible(isUiVisible: Boolean) {
        viewBinding.root.removeCallbacks(hideUiRunnable)
        if (viewBinding.appbarTop.isVisible != isUiVisible) {
            if (isAnimationsEnabled) {
                val transition = TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .setDuration(250)
                    .addTransition(Slide(Gravity.TOP).addTarget(viewBinding.appbarTop))
                    .addTransition(Fade().addTarget(viewBinding.infoBar))
                viewBinding.buttonReaderTopBack?.let {
                    transition.addTransition(Slide(Gravity.TOP).addTarget(it))
                }
                viewBinding.buttonReaderTopSettings?.let {
                    transition.addTransition(Slide(Gravity.TOP).addTarget(it))
                }
                viewBinding.toolbarDocked?.let {
                    transition.addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                }
                viewBinding.customReaderTopLeftStack?.let {
                    transition.addTransition(TransitionSet().apply {
                        ordering = TransitionSet.ORDERING_TOGETHER
                        addTransition(Slide(Gravity.TOP).addTarget(it))
                        addTransition(Fade().addTarget(it))
                    })
                }
                viewBinding.customReaderBottomProgressLayout?.let {
                    transition.addTransition(TransitionSet().apply {
                        ordering = TransitionSet.ORDERING_TOGETHER
                        addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                        addTransition(Fade().addTarget(it))
                    })
                }
                viewBinding.customReaderBottomRightStack?.let {
                    transition.addTransition(TransitionSet().apply {
                        ordering = TransitionSet.ORDERING_TOGETHER
                        addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                        addTransition(Fade().addTarget(it))
                    })
                }
                TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            }
            val isFullscreen = settings.isReaderFullscreenEnabled
            viewBinding.appbarTop.isVisible = isUiVisible
            viewBinding.buttonReaderTopBack?.isVisible = isUiVisible
            viewBinding.buttonReaderTopSettings?.isVisible = isUiVisible
            viewBinding.toolbarDocked?.isVisible = isUiVisible
            viewBinding.customReaderTopLeftStack?.isVisible = isUiVisible
            viewBinding.customReaderBottomProgressLayout?.isVisible = isUiVisible
            viewBinding.customReaderBottomRightStack?.isVisible = isUiVisible
            viewBinding.infoBar.isGone = isUiVisible || (!viewModel.isInfoBarEnabled.value)
            viewBinding.infoBar.isTimeVisible = isFullscreen
            updateScrollTimerButton()
            updateEndChapterActions(viewModel.uiState.value)
            systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
            hideReaderNavigationBar()
            viewBinding.root.requestApplyInsets()
        }
        if (isUiVisible) {
            viewBinding.root.postDelayed(hideUiRunnable, TimeUnit.SECONDS.toMillis(3))
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        viewBinding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemBars.top
            rightMargin = systemBars.right
            leftMargin = systemBars.left
        }
        val topButtonMargin = systemBars.top + resources.getDimensionPixelOffset(R.dimen.margin_normal)
        viewBinding.buttonReaderTopBack?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topButtonMargin
            leftMargin = systemBars.left + resources.getDimensionPixelOffset(R.dimen.margin_normal)
        }
        viewBinding.buttonReaderTopSettings?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topButtonMargin
            rightMargin = systemBars.right + resources.getDimensionPixelOffset(R.dimen.margin_normal)
        }
        viewBinding.toolbarDocked?.let { toolbarDocked ->
            toolbarDocked.updatePadding(
                left = systemBars.left + resources.getDimensionPixelOffset(R.dimen.margin_small),
                right = systemBars.right + resources.getDimensionPixelOffset(R.dimen.margin_small),
                bottom = systemBars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_small),
            )
            toolbarDocked.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left + resources.getDimensionPixelOffset(R.dimen.margin_small)
                rightMargin = systemBars.right + resources.getDimensionPixelOffset(R.dimen.margin_small)
                bottomMargin = resources.getDimensionPixelOffset(R.dimen.margin_small)
            }
		}
		viewBinding.infoBar.updatePadding(
			top = resources.getDimensionPixelOffset(R.dimen.margin_small),
			bottom = systemBars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_small),
		)
        val marginNormal = resources.getDimensionPixelOffset(R.dimen.margin_normal)
        viewBinding.customReaderTopLeftStack?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemBars.top + marginNormal
            leftMargin = systemBars.left + marginNormal
        }
        viewBinding.customReaderBottomProgressLayout?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = systemBars.bottom + marginNormal
            leftMargin = systemBars.left + marginNormal
            rightMargin = systemBars.right + marginNormal
        }
        viewBinding.customReaderBottomRightStack?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val extraMargin = if (::settings.isInitialized && settings.isScrollAdvanceEnabled) {
                (104 * resources.displayMetrics.density).toInt() // 48dp base + 56dp scroll advance height
            } else {
                (48 * resources.displayMetrics.density).toInt() // 48dp base
            }
            bottomMargin = systemBars.bottom + marginNormal + extraMargin
            rightMargin = systemBars.right + marginNormal
        }
        val innerInsets = Insets.of(
            systemBars.left,
            if (viewBinding.appbarTop.isVisible) viewBinding.appbarTop.height else systemBars.top,
            systemBars.right,
            viewBinding.toolbarDocked?.takeIf { it.isVisible }?.height ?: systemBars.bottom,
        )
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), innerInsets)
            .build()
    }

    override fun switchPageBy(delta: Int) {
        readerManager.currentReader?.switchPageBy(delta)
    }

    override fun switchChapterBy(delta: Int) {
        viewModel.switchChapterBy(delta)
    }

    override fun openMenu() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        val currentMode = readerManager.currentMode ?: return
        router.showReaderConfigSheet(currentMode)
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return readerManager.currentReader?.scrollBy(delta, smooth) == true
    }

    override fun toggleUiVisibility() {
        setUiIsVisible(!viewBinding.appbarTop.isVisible)
    }

    override fun isReaderResumed(): Boolean {
        val reader = readerManager.currentReader ?: return false
        return reader.isResumed && supportFragmentManager.fragments.lastOrNull() === reader
    }

    override fun onBookmarkClick() {
        viewModel.toggleBookmark()
    }

    override fun onSavePageClick() {
        viewModel.saveCurrentPage(pageSaveHelper)
    }

    override fun onDownloadChapterClick() {
        viewModel.downloadCurrentChapter()
    }

    override fun onScrollTimerClick(isLongClick: Boolean) {
        if (isLongClick) {
            scrollTimer.setActive(!scrollTimer.isActive.value)
        } else {
            viewBinding.timerControl.showOrHide()
        }
    }

    override fun toggleScreenOrientation() {
        if (screenOrientationHelper.toggleScreenOrientation()) {
            val anchor = if (viewBinding.customReaderBottomProgressLayout?.isVisible == true) {
                viewBinding.customReaderBottomProgressLayout
            } else {
                null
            }
            Snackbar.make(
                viewBinding.container,
                if (screenOrientationHelper.isLocked) {
                    R.string.screen_rotation_locked
                } else {
                    R.string.screen_rotation_unlocked
                },
                Snackbar.LENGTH_SHORT,
            ).setAnchorView(anchor)
                .show()
        }
    }

    override fun switchPageTo(index: Int) {
        val pages = viewModel.getCurrentChapterPages()
        val page = pages?.getOrNull(index) ?: return
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return
        onPageSelected(ReaderPage(page, index, chapterId))
    }

    private fun onReaderBarChanged(isBarEnabled: Boolean) {
        viewBinding.infoBar.isVisible = isBarEnabled && viewBinding.appbarTop.isGone
    }

    private fun hideReaderNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun onUiStateChanged(pair: Pair<ReaderUiState?, ReaderUiState?>) {
        val (previous: ReaderUiState?, uiState: ReaderUiState?) = pair
        title = uiState?.mangaName ?: getString(R.string.loading_)
        viewBinding.infoBar.update(uiState)
        if (uiState == null) {
            supportActionBar?.subtitle = null
            viewBinding.actionsView.setReaderInfo(
                chapterTitle = getString(R.string.loading_),
                chapterNumber = 0,
                chaptersTotal = 0,
                currentPage = 0,
                totalPages = 0,
            )
            viewBinding.actionsView.setSliderValue(0, 1)
            viewBinding.actionsView.isSliderEnabled = false
            updateEndChapterActions(null)

            viewBinding.textViewReaderTitleCustom?.text = getString(R.string.loading_)
            viewBinding.textViewReaderChapterTitleCustom?.text = getString(R.string.loading_)
            viewBinding.textViewReaderChapterTitleCustom?.isVisible = true
            viewBinding.customButtonPrevChapter?.isEnabled = false
            viewBinding.customButtonPrevChapter?.alpha = 0.36f
            viewBinding.customButtonNextChapter?.isEnabled = false
            viewBinding.customButtonNextChapter?.alpha = 0.36f
            return
        }
        val chapterTitle = uiState.getChapterTitle(resources)
        supportActionBar?.title = uiState.mangaName
        supportActionBar?.subtitle = when {
            uiState.incognito -> getString(R.string.incognito_mode)
            else -> chapterTitle
        }
        viewBinding.actionsView.setReaderInfo(
            chapterTitle = chapterTitle,
            chapterNumber = uiState.chapterNumber,
            chaptersTotal = uiState.chaptersTotal,
            currentPage = uiState.currentPage + 1,
            totalPages = uiState.totalPages,
            percent = uiState.percent,
        )
        if (
            settings.isReaderChapterToastEnabled &&
            chapterTitle != previous?.getChapterTitle(resources) &&
            chapterTitle.isNotEmpty()
        ) {
            viewBinding.toastView.showTemporary(chapterTitle, TOAST_DURATION)
        }
        if (uiState.isSliderAvailable()) {
            viewBinding.actionsView.setSliderValue(
                value = uiState.currentPage,
                max = uiState.totalPages - 1,
            )
        } else {
            viewBinding.actionsView.setSliderValue(0, 1)
        }
        viewBinding.actionsView.isSliderEnabled = uiState.isSliderAvailable()
        viewBinding.actionsView.isNextEnabled = uiState.hasNextChapter()
        viewBinding.actionsView.isPrevEnabled = uiState.hasPreviousChapter()
        updateEndChapterActions(uiState)
        updateCustomScrollAdvance()

        // Custom UI updates
        viewBinding.textViewReaderTitleCustom?.text = uiState.mangaName

        val chapter = uiState.chapter
        val titleNumber = chapter.numberString()
        val parts = chapterTitle.split(Regex("[:\\-]"), 2)
        val chapterPrefix = parts.getOrNull(0)?.trim() ?: chapterTitle
        val chapterName = parts.getOrNull(1)?.trim() ?: ""

        val displayPrefix = if (titleNumber != null && chapterPrefix.contains(titleNumber)) {
            chapterPrefix.replace(titleNumber, uiState.chapterNumber.toString())
        } else {
            chapterPrefix
        }

        if (chapterName.isNotEmpty()) {
            viewBinding.textViewReaderChapterTitleCustom?.text = chapterName
            viewBinding.textViewReaderChapterTitleCustom?.isVisible = true
        } else {
            viewBinding.textViewReaderChapterTitleCustom?.text = if (uiState.chaptersTotal > 0) {
                "$displayPrefix / ${uiState.chaptersTotal}"
            } else {
                displayPrefix
            }
            viewBinding.textViewReaderChapterTitleCustom?.isVisible = true
        }

        val hasPrev = uiState.hasPreviousChapter()
        val hasNext = uiState.hasNextChapter()
        viewBinding.customButtonPrevChapter?.isEnabled = hasPrev
        viewBinding.customButtonPrevChapter?.alpha = if (hasPrev) 1f else 0.36f
        viewBinding.customButtonNextChapter?.isEnabled = hasNext
        viewBinding.customButtonNextChapter?.alpha = if (hasNext) 1f else 0.36f
    }

    private fun updateEndChapterActions(uiState: ReaderUiState?) {
        val actions = viewBinding.endChapterActions ?: return
        actions.isVisible = false
        if (uiState == null) {
            return
        }
        viewBinding.textEndChapter?.text = uiState.getChapterTitle(resources)
        viewBinding.buttonEndPrevChapter?.isEnabled = uiState.hasPreviousChapter()
        viewBinding.buttonEndPrevChapter?.alpha = if (uiState.hasPreviousChapter()) 1f else 0.36f
        viewBinding.buttonEndNextChapter?.isEnabled = uiState.hasNextChapter()
        viewBinding.buttonEndNextChapter?.alpha = if (uiState.hasNextChapter()) 1f else 0.36f
    }

    private fun updateScrollTimerButton() {
        val button = viewBinding.buttonTimer ?: return
        val isButtonVisible = scrollTimer.isActive.value
            && settings.isReaderAutoscrollFabVisible
            && !viewBinding.appbarTop.isVisible
            && !viewBinding.timerControl.isVisible
        if (button.isVisible != isButtonVisible) {
            val transition = Fade().addTarget(button)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            button.isVisible = isButtonVisible
        }
    }

    // Observe foldable window layout to auto-enable double-page if configured
    private fun observeWindowLayout() {
        WindowInfoTracker.getOrCreate(this)
            .windowLayoutInfo(this)
            .onEach { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val unfolded = when (fold?.state) {
                    FoldingFeature.State.HALF_OPENED, FoldingFeature.State.FLAT -> true
                    else -> false
                }
                if (unfolded != isFoldUnfolded) {
                    isFoldUnfolded = unfolded
                    applyDoubleModeAuto()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun askForIncognitoMode() {
        buildAlertDialog(this, isCentered = true) {
            var dontAskAgain = false
            val listener = DialogInterface.OnClickListener { _, which ->
                if (which == DialogInterface.BUTTON_NEUTRAL) {
                    finishAfterTransition()
                } else {
                    viewModel.setIncognitoMode(which == DialogInterface.BUTTON_POSITIVE, dontAskAgain)
                }
            }
            setCheckbox(R.string.dont_ask_again, dontAskAgain) { _, isChecked ->
                dontAskAgain = isChecked
            }
            setIcon(R.drawable.ic_incognito)
            setTitle(R.string.incognito_mode)
            setMessage(R.string.incognito_mode_hint_nsfw)
            setPositiveButton(R.string.incognito, listener)
            setNegativeButton(R.string.disable, listener)
            setNeutralButton(android.R.string.cancel, listener)
            setOnCancelListener { finishAfterTransition() }
            setCancelable(true)
        }.show()
    }

    private fun showDownloadOptionsDialog() {
        ReaderDownloadSheet().show(supportFragmentManager, "ReaderDownloadSheet")
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == AppSettings.KEY_READER_SCROLL_ADVANCE) {
            updateCustomScrollAdvance()
        }
        if (key == AppSettings.KEY_READER_LONG_SCREENSHOT) {
            updateScreenshotButtonVisibility()
        }
        if (key == AppSettings.KEY_READER_ORIENTATION) {
            screenOrientationHelper.applySettings(force = true)
        }
        if (key == AppSettings.KEY_READER_FULLSCREEN) {
            val isUiVisible = viewBinding.appbarTop.isVisible
            val isFullscreen = settings.isReaderFullscreenEnabled
            viewBinding.infoBar.isTimeVisible = isFullscreen
            systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
            hideReaderNavigationBar()
        }
    }

    private fun updateCustomScrollAdvance() {
        val isEnabled = settings.isScrollAdvanceEnabled
        viewBinding.layoutCustomScrollAdvance?.isVisible = isEnabled
        
        if (isEnabled) {
            val uiState = viewModel.uiState.value ?: return
            
            if (uiState.isSliderAvailable()) {
                viewBinding.customSlider?.valueTo = (uiState.totalPages - 1).toFloat()
                viewBinding.customSlider?.setValueRounded(uiState.currentPage.toFloat())
                viewBinding.customSlider?.isEnabled = true
            } else {
                viewBinding.customSlider?.valueTo = 1f
                viewBinding.customSlider?.setValueRounded(0f)
                viewBinding.customSlider?.isEnabled = false
            }
        }
        viewBinding.root.requestApplyInsets()
    }

    private fun updateScreenshotButtonVisibility() {
        viewBinding.customButtonScreenshot?.isVisible = settings.isReaderLongScreenshotEnabled
    }

    private fun refreshCurrentChapter() {
        viewModel.refreshCurrentChapter()
    }

    // Screenshot Session State
    private var isScreenshotSessionActive = false
    private val screenshotSegments = mutableListOf<Bitmap>()
    private var screenshotAccumulatedDy = 0
    private var screenshotCurrentScrollOffset = 0
    private var screenshotMaxScrollOffset = 0
    private var activeScreenshotRecyclerView: RecyclerView? = null
    private var activeScrollListener: RecyclerView.OnScrollListener? = null
    private var activePageCallback: ViewPager2.OnPageChangeCallback? = null

    private fun toggleLongScreenshotSession() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
            ?: findViewById<ViewPager2>(R.id.pager)?.getChildAt(0) as? RecyclerView
        
        if (recyclerView == null) {
            Snackbar.make(viewBinding.root, "No active reader view found", Snackbar.LENGTH_LONG).show()
            return
        }

        if (!isScreenshotSessionActive) {
            // Start Session
            recycleScreenshotSegments()
            isScreenshotSessionActive = true
            activeScreenshotRecyclerView = recyclerView
            screenshotAccumulatedDy = 0
            screenshotCurrentScrollOffset = 0
            screenshotMaxScrollOffset = 0
            viewBinding.layoutLongScreenshotOverlay?.isVisible = true
            viewBinding.customButtonScreenshot?.setIconResource(R.drawable.ic_check)
            
            // Forward touches
            val touchTarget = findViewById<ViewPager2>(R.id.pager) ?: recyclerView
            viewBinding.layoutLongScreenshotOverlay?.setOnTouchListener { _, event ->
                touchTarget.dispatchTouchEvent(event)
                true
            }

            // Capture initial viewport
            captureCurrentViewport(recyclerView)?.let(screenshotSegments::add)

            // Track vertical scrolling (for Webtoon/Vertical modes)
            val scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) {
                        screenshotCurrentScrollOffset += dy
                        if (screenshotCurrentScrollOffset > screenshotMaxScrollOffset) {
                            val diff = screenshotCurrentScrollOffset - screenshotMaxScrollOffset
                            screenshotAccumulatedDy += diff
                            screenshotMaxScrollOffset = screenshotCurrentScrollOffset
                        }
                        if (screenshotAccumulatedDy >= LONG_SCREENSHOT_MIN_SEGMENT_PX) {
                            captureScrolledSegment(rv, force = false)
                        }
                    } else if (dy < 0) {
                        screenshotCurrentScrollOffset += dy
                    }
                }
            }
            recyclerView.addOnScrollListener(scrollListener)
            activeScrollListener = scrollListener

            // Track page changes (for ViewPager2/standard/reversed pager modes)
            val viewPager = findViewById<ViewPager2>(R.id.pager)
            if (viewPager != null) {
                val pageCallback = object : ViewPager2.OnPageChangeCallback() {
                    private var lastPage = viewPager.currentItem
                    override fun onPageSelected(position: Int) {
                        if (position != lastPage && isScreenshotSessionActive) {
                            viewPager.post {
                                val rv = viewPager.getChildAt(0) as? RecyclerView ?: return@post
                                captureCurrentViewport(rv)?.let(screenshotSegments::add)
                            }
                            lastPage = position
                        }
                    }
                }
                viewPager.registerOnPageChangeCallback(pageCallback)
                activePageCallback = pageCallback
            }
            
            Snackbar.make(viewBinding.root, "Scroll down to capture or click checkmark to finish", Snackbar.LENGTH_LONG).show()
        } else {
            // End Session & Save
            captureScrolledSegment(recyclerView, force = true)
            isScreenshotSessionActive = false
            viewBinding.layoutLongScreenshotOverlay?.isVisible = false
            viewBinding.layoutLongScreenshotOverlay?.setOnTouchListener(null)
            viewBinding.customButtonScreenshot?.setIconResource(R.drawable.ic_screenshot)

            // Detach Listeners
            activeScrollListener?.let {
                recyclerView.removeOnScrollListener(it)
                activeScrollListener = null
            }
            val viewPager = findViewById<ViewPager2>(R.id.pager)
            activePageCallback?.let {
                viewPager?.unregisterOnPageChangeCallback(it)
                activePageCallback = null
            }
            activeScreenshotRecyclerView = null

            // Stitch segments
            lifecycleScope.launch(Dispatchers.Default) {
                if (screenshotSegments.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(viewBinding.root, "No content captured", Snackbar.LENGTH_LONG).show()
                    }
                    return@launch
                }

                var totalWidth = 0
                var totalHeight = 0
                for (seg in screenshotSegments) {
                    totalWidth = maxOf(totalWidth, seg.width)
                    totalHeight += seg.height
                }

                if (totalWidth <= 0 || totalHeight <= 0) {
                    recycleScreenshotSegments()
                    withContext(Dispatchers.Main) {
                        Snackbar.make(viewBinding.root, "Capture dimensions are invalid", Snackbar.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val stitchedBitmap = try {
                    Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    recycleScreenshotSegments()
                    withContext(Dispatchers.Main) {
                        Snackbar.make(viewBinding.root, "Failed: image size exceeds available memory", Snackbar.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val canvas = Canvas(stitchedBitmap)
                var currentY = 0f
                for (seg in screenshotSegments) {
                    canvas.drawBitmap(seg, 0f, currentY, null)
                    currentY += seg.height
                }
                recycleScreenshotSegments()

                val manga = viewModel.getMangaOrNull()
                val chapter = viewModel.getCurrentState()?.let { cs ->
                    viewModel.mangaDetails.value?.allChapters?.find { it.id == cs.chapterId }
                }
                
                saveBitmap(stitchedBitmap, manga?.title ?: "Manga", chapter?.numberString())
                stitchedBitmap.recycle()
            }
        }
    }

    private fun captureCurrentViewport(recyclerView: RecyclerView): Bitmap? {
        val viewWidth = recyclerView.width
        val viewHeight = recyclerView.height
        if (viewWidth <= 0 || viewHeight <= 0) {
            return null
        }
        return try {
            Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888).also {
                recyclerView.draw(Canvas(it))
            }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun captureScrolledSegment(recyclerView: RecyclerView, force: Boolean) {
        val diff = screenshotAccumulatedDy
        if (diff <= 0 || (!force && diff < LONG_SCREENSHOT_MIN_SEGMENT_PX)) {
            return
        }
        val viewport = captureCurrentViewport(recyclerView) ?: return
        val sliceHeight = minOf(diff, viewport.height)
        if (sliceHeight <= 0) {
            viewport.recycle()
            return
        }
        val segment = try {
            Bitmap.createBitmap(viewport, 0, viewport.height - sliceHeight, viewport.width, sliceHeight)
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            viewport.recycle()
        }
        if (segment != null) {
            screenshotSegments.add(segment)
            screenshotAccumulatedDy -= sliceHeight
        }
    }

    private fun recycleScreenshotSegments() {
        screenshotSegments.forEach { it.recycle() }
        screenshotSegments.clear()
    }

    private suspend fun saveBitmap(bitmap: Bitmap, mangaTitle: String, chapterNumber: String?) = withContext(Dispatchers.IO) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val proposedName = "${mangaTitle.toFileNameSafe().take(12)}-${chapterNumber ?: "chapter"}-long_screenshot_$dateStr.png"
        
        val saveDir = settings.getPagesSaveDir(this@ReaderActivity)
        val destinationUri = if (saveDir != null) {
            val destFile = saveDir.createFile("image/png", proposedName.substringBeforeLast('.'))
            destFile?.uri
        } else {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, proposedName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Tarumi")
                }
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        }
        
        withContext(Dispatchers.Main) {
            if (destinationUri != null) {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(destinationUri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    Snackbar.make(viewBinding.root, "Screenshot saved successfully", Snackbar.LENGTH_LONG).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Snackbar.make(viewBinding.root, "Failed to write screenshot data", Snackbar.LENGTH_LONG).show()
                }
            } else {
                Snackbar.make(viewBinding.root, "Failed to create destination file", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {

        private const val TOAST_DURATION = 2000L
        private const val LONG_SCREENSHOT_MIN_SEGMENT_PX = 48
    }
}
