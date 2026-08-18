package org.koitharu.kotatsu.settings.sources.quality

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivitySourceHealthBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner

@AndroidEntryPoint
class MetadataQualityActivity : BaseActivity<ActivitySourceHealthBinding>(),
	OnListItemClickListener<MetadataQualityItem>,
	AppBarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<MetadataQualityViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourceHealthBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		viewBinding.toolbar.setTitle(R.string.metadata_quality_dashboard)
		viewBinding.textViewSummary.setText(R.string.metadata_quality_dashboard_summary)
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		val qualityAdapter = MetadataQualityAdapter(this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = qualityAdapter
		}
		viewModel.content.observe(this, qualityAdapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onItemClick(item: MetadataQualityItem, view: View) {
		router.openList(item.source, null, null)
	}
}
