package org.koitharu.kotatsu.tracker.ui.updates

import android.os.Bundle
import androidx.core.view.isGone
import org.koitharu.kotatsu.core.ui.FragmentContainerActivity
import org.koitharu.kotatsu.tracker.ui.feed.FeedFragment

class UpdatesActivity : FragmentContainerActivity(FeedFragment::class.java) {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		appBar.isGone = true
	}
}
