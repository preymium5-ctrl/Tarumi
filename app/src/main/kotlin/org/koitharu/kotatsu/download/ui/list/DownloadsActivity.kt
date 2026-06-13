package org.koitharu.kotatsu.download.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivityDownloadsBinding
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsActivity : BaseActivity<ActivityDownloadsBinding>(),
	DownloadItemListener,
	ListSelectionController.Callback {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var scheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<DownloadsViewModel>()
	private lateinit var selectionController: ListSelectionController

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDownloadsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = false, showUpAsClose = false)
		title = ""
		viewBinding.toolbar.title = ""
		viewBinding.collapsingToolbarLayout.title = ""
		val downloadsAdapter = DownloadsAdapter(this, this)
		val decoration = TypedListSpacingDecoration(this, false)
		selectionController = ListSelectionController(
			appCompatDelegate = delegate,
			decoration = DownloadsSelectionDecoration(this),
			registryOwner = this,
			callback = this,
		)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(decoration)
			adapter = downloadsAdapter
			selectionController.attachToRecyclerView(this)
			RecyclerScrollKeeper(this).attach()
		}
		setupBottomNav()
		viewBinding.buttonMenu.setOnClickListener(::showDownloadsMenu)
		viewModel.items.observe(this, downloadsAdapter)
		viewModel.stats.observe(this) { stats ->
			viewBinding.textDownloadsActive.text = stats.active.toString()
			viewBinding.textDownloadsChapters.text = stats.chapters.toString()
			viewBinding.textDownloadsCompleted.text = stats.completed.toString()
		}
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.recyclerView))
		viewBinding.buttonNsfw.setOnClickListener {
			viewModel.setNsfwMode(viewBinding.buttonNsfw.isChecked)
		}
		viewModel.isNsfwMode.observe(this) { isChecked ->
			viewBinding.buttonNsfw.isChecked = isChecked
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom + 128.dp,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		viewBinding.bottomNav.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onItemClick(item: DownloadItemModel, view: View) {
		if (selectionController.onItemClick(item.id.mostSignificantBits)) {
			return
		}
		val manga = item.manga ?: return
		if (manga.isNsfw()) {
			router.openNsfwBrowserDetails(manga)
		} else {
			router.openDetails(manga)
		}
	}

	override fun onItemLongClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemLongClick(view, item.id.mostSignificantBits)
	}

	override fun onItemContextClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemContextClick(view, item.id.mostSignificantBits)
	}

	override fun onExpandClick(item: DownloadItemModel) {
		if (!selectionController.onItemClick(item.id.mostSignificantBits)) {
			viewModel.expandCollapse(item)
		}
	}

	override fun onCancelClick(item: DownloadItemModel) {
		viewModel.cancel(item.id)
	}

	override fun onPauseClick(item: DownloadItemModel) {
		scheduler.pause(item.id)
	}

	override fun onResumeClick(item: DownloadItemModel) {
		scheduler.resume(item.id)
	}

	override fun onSkipClick(item: DownloadItemModel) {
		scheduler.skip(item.id)
	}

	override fun onSkipAllClick(item: DownloadItemModel) {
		scheduler.skipAll(item.id)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding.recyclerView.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_downloads, menu)
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_resume -> {
				viewModel.resume(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_pause -> {
				viewModel.pause(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_cancel -> {
				viewModel.cancel(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_remove -> {
				viewModel.remove(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_select_all -> {
				controller.addAll(viewModel.allIds())
				true
			}

			else -> false
		}
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val snapshot = viewModel.snapshot(controller.peekCheckedIds())
		var canPause = true
		var canResume = true
		var canCancel = true
		var canRemove = true
		for (item in snapshot) {
			canPause = canPause and item.canPause
			canResume = canResume and item.canResume
			canCancel = canCancel and !item.workState.isFinished
			canRemove = canRemove and item.workState.isFinished
		}
		menu.findItem(R.id.action_pause)?.isVisible = canPause
		menu.findItem(R.id.action_resume)?.isVisible = canResume
		menu.findItem(R.id.action_cancel)?.isVisible = canCancel
		menu.findItem(R.id.action_remove)?.isVisible = canRemove
		return super.onPrepareActionMode(controller, mode, menu)
	}

	private fun setupBottomNav() {
		val nav = viewBinding.bottomNav
		if (nav.menu.size() == 0) {
			for (item in settings.mainNavItems) {
				if (!item.isAvailable(settings)) {
					continue
				}
				nav.menu.add(Menu.NONE, item.id, Menu.NONE, item.title).setIcon(item.icon)
				if (nav.menu.size() >= nav.maxItemCount) {
					break
				}
			}
		}
		nav.selectedItemId = R.id.nav_favorites
		nav.setOnItemSelectedListener { item ->
			when (item.itemId) {
				R.id.nav_settings -> {
					router.openSettings()
					false
				}

				R.id.nav_favorites -> {
					finishAfterTransition()
					true
				}

				else -> {
					openMainNav(item.itemId)
					true
				}
			}
		}
	}

	private fun openMainNav(itemId: Int) {
		startActivity(
			Intent(this, MainActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
				.putExtra(AppRouter.KEY_NAV_ITEM, itemId),
		)
		finishAfterTransition()
	}

	private fun showDownloadsMenu(anchor: View) {
		val popup = PopupMenu(this, anchor)
		with(popup.menu) {
			if (viewModel.hasActiveWorks.value == true) {
				add(Menu.NONE, R.id.action_pause, Menu.NONE, R.string.pause)
			}
			if (viewModel.hasPausedWorks.value == true) {
				add(Menu.NONE, R.id.action_resume, Menu.NONE, R.string.resume)
			}
			if (viewModel.hasCancellableWorks.value == true) {
				add(Menu.NONE, R.id.action_cancel_all, Menu.NONE, R.string.cancel_all)
			}
			add(Menu.NONE, R.id.action_remove_completed, Menu.NONE, R.string.remove_completed)
			add(Menu.NONE, R.id.action_settings, Menu.NONE, R.string.settings)
		}
		popup.setOnMenuItemClickListener { item ->
			when (item.itemId) {
				R.id.action_pause -> viewModel.pauseAll()
				R.id.action_resume -> viewModel.resumeAll()
				R.id.action_cancel_all -> confirmCancelAll()
				R.id.action_remove_completed -> confirmRemoveCompleted()
				R.id.action_settings -> router.openDownloadsSetting()
				else -> return@setOnMenuItemClickListener false
			}
			true
		}
		popup.show()
	}

	private fun confirmCancelAll() {
		buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.cancel_all)
			setMessage(R.string.cancel_all_downloads_confirm)
			setIcon(R.drawable.ic_cancel_multiple)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.confirm) { _, _ -> viewModel.cancelAll() }
		}.show()
	}

	private fun confirmRemoveCompleted() {
		buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.remove_completed)
			setMessage(R.string.remove_completed_downloads_confirm)
			setIcon(R.drawable.ic_clear_all)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.removeCompleted() }
		}.show()
	}

	private val Int.dp: Int
		get() = (this * resources.displayMetrics.density).toInt()
}
