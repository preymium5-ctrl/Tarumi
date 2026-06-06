package org.koitharu.kotatsu.main.ui

import android.Manifest
import android.app.BackgroundServiceStartNotAllowedException
import android.app.ServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
import com.google.android.material.search.SearchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.ai.ui.AskAiFragment
import org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupService
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.VoiceInputContract
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.widgets.SlidingBottomNavigationView
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityMainBinding
import org.koitharu.kotatsu.databinding.ViewTaruAppbarBinding
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.home.ui.HomeFragment
import org.koitharu.kotatsu.local.ui.LocalIndexUpdateService
import org.koitharu.kotatsu.local.ui.LocalStorageCleanupWorker
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.remotelist.ui.MangaSearchMenuProvider
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionItemCallback
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListenerImpl
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionMenuProvider
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionViewModel
import org.koitharu.kotatsu.search.ui.suggestion.adapter.SearchSuggestionAdapter
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>(), AppBarOwner, BottomNavOwner,
	View.OnClickListener,
	SearchSuggestionItemCallback.SuggestionItemListener,
	MainNavigationDelegate.OnFragmentChangedListener,
	View.OnLayoutChangeListener,
	SearchView.TransitionListener {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<MainViewModel>()
	private val searchSuggestionViewModel by viewModels<SearchSuggestionViewModel>()
	private val voiceInputLauncher = registerForActivityResult(VoiceInputContract()) { result ->
		if (result != null) {
			viewBinding.searchView.setText(result)
		}
	}
	private lateinit var navigationDelegate: MainNavigationDelegate
	private lateinit var fadingAppbarMediator: FadingAppbarMediator
	private var taruAppBar: ViewTaruAppbarBinding? = null
	private var updatePromptShown = false

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val bottomNav: SlidingBottomNavigationView?
		get() = viewBinding.bottomNav

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityMainBinding.inflate(layoutInflater))
		setSupportActionBar(viewBinding.searchBar)

		taruAppBar = viewBinding.taruAppBar
		taruAppBar?.buttonNotifications?.setOnClickListener(this)
		taruAppBar?.buttonSearch?.setOnClickListener(this)
		taruAppBar?.buttonHistory?.setOnClickListener(this)
		taruAppBar?.buttonMenu?.setOnClickListener(this)

		viewBinding.fab?.setOnClickListener(this)
		viewBinding.navRail?.headerView?.findViewById<View>(R.id.railFab)?.setOnClickListener(this)
		fadingAppbarMediator =
			FadingAppbarMediator(viewBinding.appbar, viewBinding.layoutSearch ?: viewBinding.searchBar)

		navigationDelegate = MainNavigationDelegate(
			navBar = checkNotNull(bottomNav ?: viewBinding.navRail),
			fragmentManager = supportFragmentManager,
			settings = settings,
		)
		navigationDelegate.addOnFragmentChangedListener(this)
		navigationDelegate.onCreate(this, savedInstanceState)
		navigateFromIntent(intent)
		viewBinding.textViewTitle?.let { tv ->
			navigationDelegate.observeTitle().observe(this) { tv.text = it }
		}

		addMenuProvider(MainMenuProvider(router, viewModel))

		val exitCallback = ExitCallback(this, viewBinding.container)
		onBackPressedDispatcher.addCallback(exitCallback)
		onBackPressedDispatcher.addCallback(navigationDelegate)

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !resources.getBoolean(R.bool.is_predictive_back_enabled)) {
			val legacySearchCallback = SearchViewLegacyBackCallback(viewBinding.searchView)
			viewBinding.searchView.addTransitionListener(legacySearchCallback)
			onBackPressedDispatcher.addCallback(legacySearchCallback)
		}

		if (savedInstanceState == null) {
			onFirstStart()
		}

		viewModel.onOpenReader.observeEvent(this, this::onOpenReader)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.container, null))
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.isResumeEnabled.observe(this, this::onResumeEnabledChanged)
		viewModel.feedCounter.observe(this, ::onFeedCounterChanged)
		viewModel.appUpdate.observe(this, MenuInvalidator(this))
		viewModel.appUpdate.observe(this) { version ->
			if (version != null && !updatePromptShown) {
				updatePromptShown = true
				showUpdateTutorial(version.name)
			}
		}
		viewModel.onFirstStart.observeEvent(this) { router.showWelcomeSheet() }
		viewModel.isBottomNavPinned.observe(this, ::setNavbarPinned)
		settings.observe(AppSettings.KEY_FLOATING_NAV).onEach {
			viewBinding.root.requestApplyInsets()
			setNavbarPinned(settings.isNavBarPinned)
		}.launchIn(lifecycleScope)
		searchSuggestionViewModel.isIncognitoModeEnabled.observe(this, this::onIncognitoModeChanged)
		viewBinding.bottomNav?.addOnLayoutChangeListener(this)
		viewBinding.searchView.addTransitionListener(this)
		viewBinding.searchView.addTransitionListener(exitCallback)
		initSearch()
		hideSystemNavigationBar()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		navigateFromIntent(intent)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) {
			hideSystemNavigationBar()
		}
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		adjustSearchUI(viewBinding.searchView.isShowing)
		navigationDelegate.syncSelectedItem()
	}

	override fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		val isAskAi = fragment is AskAiFragment
		if (fromUser) {
			actionModeDelegate.finishActionMode()
		}
		adjustFabVisibility(topFragment = fragment)
		adjustAppbar(topFragment = fragment)
		adjustContainerBehavior(isFullscreen = isAskAi)
		bottomNav?.showOrHide(!isAskAi)
		updateContainerBottomMargin()
		viewBinding.container.requestApplyInsets()
		if (fromUser) {
			viewBinding.appbar.setExpanded(true)
		}
	}

	override fun addMenuProvider(provider: MenuProvider, owner: LifecycleOwner, state: Lifecycle.State) {
		if (provider !is MangaSearchMenuProvider) { // do not duplicate search menu item
			super.addMenuProvider(provider, owner, state)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.fab, R.id.railFab -> viewModel.openLastReader()
			R.id.buttonNotifications -> router.openMangaUpdates()
			R.id.buttonSearch -> openSearch()
			R.id.buttonHistory -> router.openHistory()
			R.id.buttonMenu -> router.openSettings()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val searchBarDefaultMargin = resources.getDimensionPixelOffset(materialR.dimen.m3_searchbar_margin_horizontal)
		viewBinding.searchBar.updateLayoutParams<MarginLayoutParams> {
			marginEnd = searchBarDefaultMargin + barsInsets.end(v)
			marginStart = if (viewBinding.navRail != null) {
				searchBarDefaultMargin
			} else {
				searchBarDefaultMargin + barsInsets.start(v)
			}
		}
		viewBinding.bottomNav?.let { nav ->
			val isFloating = settings.isFloatingNavBar
			if (isFloating) {
				nav.updatePadding(left = barsInsets.left, right = barsInsets.right, bottom = 0)
				nav.updateLayoutParams<MarginLayoutParams> {
					marginStart = resources.getDimensionPixelOffset(R.dimen.margin_normal)
					marginEnd = resources.getDimensionPixelOffset(R.dimen.margin_normal)
					bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal)
				}
				nav.elevation = 6f * resources.displayMetrics.density
			} else {
				nav.updatePadding(left = barsInsets.left, right = barsInsets.right, bottom = barsInsets.bottom)
				nav.updateLayoutParams<MarginLayoutParams> {
					marginStart = 0
					marginEnd = 0
					bottomMargin = 0
				}
				nav.elevation = 0f
			}
		}
		viewBinding.navRail?.updateLayoutParams<MarginLayoutParams> {
			marginStart = barsInsets.start(v)
			topMargin = barsInsets.top
			bottomMargin = barsInsets.bottom
		}
		updateContainerBottomMargin()
		return insets.consume(v, typeMask, start = viewBinding.navRail != null).also {
			handleSearchSuggestionsInsets(it)
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		if (top != oldTop || bottom != oldBottom) {
			updateContainerBottomMargin()
		}
	}

	override fun onStateChanged(
		searchView: SearchView,
		previousState: SearchView.TransitionState,
		newState: SearchView.TransitionState,
	) {
		val wasOpened = previousState >= SearchView.TransitionState.SHOWING
		val isOpened = newState >= SearchView.TransitionState.SHOWING
		if (isOpened != wasOpened) {
			adjustSearchUI(isOpened)
		}
		if (newState == SearchView.TransitionState.HIDDEN) {
			searchView.clearFocus()
			searchView.isVisible = false
		} else if (isOpened) {
			searchView.isVisible = true
		}
	}

	override fun onRemoveQuery(query: String) {
		searchSuggestionViewModel.deleteQuery(query)
	}

	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		adjustFabVisibility()
		bottomNav?.hide()
		(viewBinding.layoutSearch ?: viewBinding.searchBar).isInvisible = true
		updateContainerBottomMargin()
	}

	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		adjustFabVisibility()
		bottomNav?.show()
		(viewBinding.layoutSearch ?: viewBinding.searchBar).isInvisible = false
		updateContainerBottomMargin()
	}

	private fun onOpenReader(manga: Manga) {
		val fab = viewBinding.fab ?: viewBinding.navRail?.headerView
		router.openReader(manga, fab)
	}

	private fun onFeedCounterChanged(counter: Int) {
		navigationDelegate.setCounter(NavItem.FEED, counter)
		taruAppBar?.textViewNotificationCount?.let { badge ->
			badge.isVisible = counter > 0
			badge.text = if (counter > 99) {
				"99+"
			} else {
				counter.toString()
			}
		}
	}

	private fun onIncognitoModeChanged(isIncognito: Boolean) {
		var options = viewBinding.searchView.getEditText().imeOptions
		options = if (isIncognito) {
			options or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
		} else {
			options and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
		}
		viewBinding.searchView.getEditText().imeOptions = options
		invalidateOptionsMenu()
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		val fab = viewBinding.fab ?: viewBinding.navRail?.headerView ?: return
		fab.isEnabled = !isLoading
	}

	private fun onResumeEnabledChanged(isEnabled: Boolean) {
		adjustFabVisibility(isResumeEnabled = isEnabled)
	}

	private fun onFirstStart() = try {
		lifecycleScope.launch(Dispatchers.Main) { // not a default `Main.immediate` dispatcher
			withContext(Dispatchers.Default) {
				LocalStorageCleanupWorker.enqueue(applicationContext)
			}
			withResumed {
				MangaPrefetchService.prefetchLast(this@MainActivity)
				requestNotificationsPermission()
				startService(Intent(this@MainActivity, LocalIndexUpdateService::class.java))
				startService(Intent(this@MainActivity, PeriodicalBackupService::class.java))
			}
		}
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
	}

	private fun adjustAppbar(topFragment: Fragment) {
		if (topFragment is AskAiFragment) {
			viewBinding.appbar.isVisible = false
			fadingAppbarMediator.unbind()
			return
		}
		viewBinding.appbar.isVisible = true
		viewBinding.appbar.fitsSystemWindows = false
		val hideHomeLandscapeHeader = topFragment is HomeFragment &&
			resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		viewBinding.textViewTitle?.visibility = if (hideHomeLandscapeHeader) View.GONE else View.VISIBLE
		(viewBinding.layoutSearch ?: viewBinding.searchBar).visibility =
			if (hideHomeLandscapeHeader) View.GONE else View.VISIBLE
		fadingAppbarMediator.unbind()
	}

	private fun adjustContainerBehavior(isFullscreen: Boolean) {
		viewBinding.container.updateLayoutParams<CoordinatorLayout.LayoutParams> {
			behavior = if (isFullscreen) {
				null
			} else if (behavior is AppBarLayout.ScrollingViewBehavior) {
				behavior
			} else {
				AppBarLayout.ScrollingViewBehavior()
			}
		}
	}

	private fun adjustFabVisibility(
		isResumeEnabled: Boolean = viewModel.isResumeEnabled.value,
		topFragment: Fragment? = navigationDelegate.primaryFragment,
		isSearchOpened: Boolean = viewBinding.searchView.isShowing,
	) {
		navigationDelegate.navRailHeader?.railFab?.isVisible = isResumeEnabled
		val fab = viewBinding.fab ?: return
		if (isResumeEnabled && !actionModeDelegate.isActionModeStarted && !isSearchOpened && topFragment is HistoryListFragment) {
			if (!fab.isVisible) {
				fab.show()
			}
		} else {
			if (fab.isVisible) {
				fab.hide()
			}
		}
	}

	private fun adjustSearchUI(isOpened: Boolean) {
		val appBarScrollFlags = if (isOpened) {
			SCROLL_FLAG_NO_SCROLL
		} else {
			SCROLL_FLAG_SCROLL or SCROLL_FLAG_ENTER_ALWAYS or SCROLL_FLAG_SNAP
		}
		viewBinding.insetsHolder.updateLayoutParams<AppBarLayout.LayoutParams> {
			scrollFlags = appBarScrollFlags
		}
		taruAppBar?.root?.isInvisible = isOpened
		adjustFabVisibility(isSearchOpened = isOpened)
		bottomNav?.showOrHide(true)
		updateContainerBottomMargin()
	}

	fun openSearchWithQuery(query: String = "") {
		val searchView = viewBinding.searchView
		searchView.isVisible = true
		searchView.bringToFront()
		bottomNav?.show()
		adjustSearchUI(isOpened = true)
		searchView.show()
		if (query.isNotBlank()) {
			searchView.editText.setText(query)
			searchView.editText.setSelection(searchView.editText.text?.length ?: 0)
		}
		searchView.editText.requestFocus()
		searchView.post {
			getSystemService<InputMethodManager>()?.showSoftInput(searchView.editText, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun showUpdateTutorial(versionName: String) {
		viewBinding.root.post {
			if (isFinishing || isDestroyed) {
				return@post
			}
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.update_tutorial_title)
				.setMessage(getString(R.string.update_tutorial_message, versionName))
				.setNegativeButton(R.string.got_it, null)
				.setPositiveButton(R.string.check_app_updates) { _, _ ->
					router.openAppUpdate()
				}
				.show()
		}
	}

	private fun openSearch() = openSearchWithQuery()

	private fun navigateFromIntent(intent: Intent?) {
		val navItemId = intent?.getIntExtra(AppRouter.KEY_NAV_ITEM, 0) ?: 0
		if (navItemId != 0) {
			navigationDelegate.selectItem(navItemId)
			intent?.removeExtra(AppRouter.KEY_NAV_ITEM)
		}
	}

	private fun requestNotificationsPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.POST_NOTIFICATIONS,
			) != PERMISSION_GRANTED
		) {
			ActivityCompat.requestPermissions(
				this,
				arrayOf(Manifest.permission.POST_NOTIFICATIONS),
				1,
			)
		}
	}

	private fun handleSearchSuggestionsInsets(insets: WindowInsetsCompat) {
		val typeMask = WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val density = resources.displayMetrics.density
		val horizontalPadding = (20 * density).toInt()
		val topPadding = (6 * density).toInt()
		val bottomPadding = (120 * density).toInt()
		viewBinding.recyclerViewSearch.setPadding(
			barsInsets.left + horizontalPadding,
			topPadding,
			barsInsets.right + horizontalPadding,
			barsInsets.bottom + bottomPadding,
		)
	}

	private fun initSearch() {
		val listener = SearchSuggestionListenerImpl(router, viewBinding.searchView, searchSuggestionViewModel)
		val adapter = SearchSuggestionAdapter(listener)
		viewBinding.searchView.setBackgroundResource(R.drawable.bg_taru_home_root)
		viewBinding.searchView.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
		viewBinding.recyclerViewSearch.setBackgroundResource(android.R.color.transparent)
		viewBinding.searchView.editText.setTextColor(ContextCompat.getColor(this, R.color.taru_text_primary))
		viewBinding.searchView.editText.setHintTextColor(ContextCompat.getColor(this, R.color.taru_text_muted))
		viewBinding.searchView.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.taru_text_primary))
		viewBinding.searchView.toolbar.navigationIcon?.setTint(
			ContextCompat.getColor(this, R.color.taru_text_primary),
		)
		viewBinding.searchView.toolbar.addMenuProvider(
			SearchSuggestionMenuProvider(this, voiceInputLauncher, searchSuggestionViewModel),
		)
		viewBinding.searchView.editText.addTextChangedListener(listener)
		viewBinding.recyclerViewSearch.adapter = adapter
		viewBinding.searchView.editText.setOnEditorActionListener(listener)

		viewBinding.searchView.observeState()
			.map { it >= SearchView.TransitionState.SHOWING }
			.distinctUntilChanged()
			.flatMapLatest { isShowing ->
				if (isShowing) {
					searchSuggestionViewModel.suggestion
				} else {
					emptyFlow()
				}
			}.observe(this, adapter)
		searchSuggestionViewModel.onError.observeEvent(
			this,
			SnackbarErrorObserver(viewBinding.recyclerViewSearch, null),
		)
		ItemTouchHelper(SearchSuggestionItemCallback(this))
			.attachToRecyclerView(viewBinding.recyclerViewSearch)
	}

	private fun setNavbarPinned(isPinned: Boolean) {
		val bottomNavBar = viewBinding.bottomNav
		bottomNavBar?.isPinned = isPinned || settings.isFloatingNavBar
		for (view in viewBinding.appbar.children) {
			val lp = view.layoutParams as? AppBarLayout.LayoutParams ?: continue
			val scrollFlags = if (isPinned) {
				lp.scrollFlags and SCROLL_FLAG_SCROLL.inv()
			} else {
				lp.scrollFlags or SCROLL_FLAG_SCROLL
			}
			if (scrollFlags != lp.scrollFlags) {
				lp.scrollFlags = scrollFlags
				view.layoutParams = lp
			}
		}
		updateContainerBottomMargin()
	}

	private fun updateContainerBottomMargin() {
		val bottomNavBar = viewBinding.bottomNav ?: return
		val newMargin = if (bottomNavBar.isPinned && bottomNavBar.isShownOrShowing) bottomNavBar.height else 0
		with(viewBinding.container) {
			val params = layoutParams as MarginLayoutParams
			if (params.bottomMargin != newMargin) {
				params.bottomMargin = newMargin
				layoutParams = params
			}
		}
	}

	private fun hideSystemNavigationBar() {
		WindowInsetsControllerCompat(window, window.decorView).run {
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			hide(WindowInsetsCompat.Type.navigationBars())
		}
	}

	private fun SearchView.observeState() = callbackFlow {
		val listener = SearchView.TransitionListener { _, _, state ->
			trySendBlocking(state)
		}
		addTransitionListener(listener)
		awaitClose { removeTransitionListener(listener) }
	}
}
