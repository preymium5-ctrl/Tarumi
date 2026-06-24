package org.koitharu.kotatsu.settings.protect

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivitySetupProtectBinding

@AndroidEntryPoint
class ProtectSetupActivity :
	BaseActivity<ActivitySetupProtectBinding>(),
	View.OnClickListener,
	CompoundButton.OnCheckedChangeListener {

	private val viewModel by viewModels<ProtectSetupViewModel>()
	private var enteredPin = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivitySetupProtectBinding.inflate(layoutInflater))

		setupKeypad()
		viewBinding.buttonCancel.setOnClickListener(this)

		viewBinding.switchBiometric.isChecked = viewModel.isBiometricEnabled
		viewBinding.switchBiometric.setOnCheckedChangeListener(this)

		viewModel.isSecondStep.observe(this, this::onStepChanged)
		viewModel.onPasswordSet.observeEvent(this) {
			finishAfterTransition()
		}
		viewModel.onPasswordMismatch.observeEvent(this) {
			viewBinding.textViewSubtitle.text = getString(R.string.passwords_mismatch)
			animateShake(viewBinding.layoutDots)
			enteredPin = ""
			updateDots()
		}
		viewModel.onClearText.observeEvent(this) {
			enteredPin = ""
			updateDots()
		}
	}

	private fun setupKeypad() {
		val digits = listOf(
			viewBinding.btn0, viewBinding.btn1, viewBinding.btn2, viewBinding.btn3,
			viewBinding.btn4, viewBinding.btn5, viewBinding.btn6, viewBinding.btn7,
			viewBinding.btn8, viewBinding.btn9
		)
		digits.forEach { btn ->
			btn.setOnClickListener {
				if (enteredPin.length < 6) {
					animateTap(btn)
					enteredPin += btn.text
					updateDots()
					if (enteredPin.length == 6) {
						viewModel.onNextClick(enteredPin)
					}
				}
			}
		}

		viewBinding.btnDelete.setOnClickListener {
			if (enteredPin.isNotEmpty()) {
				animateTap(viewBinding.btnDelete)
				enteredPin = enteredPin.dropLast(1)
				updateDots()
			}
		}
	}

	private fun updateDots() {
		val dotsLayout = viewBinding.layoutDots
		for (i in 0 until dotsLayout.childCount) {
			val dot = dotsLayout.getChildAt(i) as? android.widget.ImageView ?: continue
			if (i < enteredPin.length) {
				dot.setImageResource(R.drawable.bg_pin_dot_filled)
				if (i == enteredPin.length - 1) {
					animateDot(dot)
				}
			} else {
				dot.setImageResource(R.drawable.bg_pin_dot_empty)
			}
		}
	}

	private fun animateTap(view: View) {
		view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
			view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
		}.start()
	}

	private fun animateDot(view: View) {
		view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).withEndAction {
			view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
		}.start()
	}

	private fun animateShake(view: View) {
		view.animate().translationX(-15f).setDuration(50).withEndAction {
			view.animate().translationX(15f).setDuration(50).withEndAction {
				view.animate().translationX(-10f).setDuration(50).withEndAction {
					view.animate().translationX(10f).setDuration(50).withEndAction {
						view.animate().translationX(0f).setDuration(50).start()
					}.start()
				}.start()
			}.start()
		}.start()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = resources.getDimensionPixelOffset(R.dimen.screen_padding)
		viewBinding.root.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finish()
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		viewModel.setBiometricEnabled(isChecked)
	}

	private fun onStepChanged(isSecondStep: Boolean) {
		viewBinding.buttonCancel.isGone = isSecondStep
		viewBinding.switchBiometric.isVisible = isSecondStep && isBiometricAvailable()
		if (isSecondStep) {
			viewBinding.textViewSubtitle.text = getString(R.string.repeat_password)
		} else {
			viewBinding.textViewSubtitle.text = getString(R.string.protect_application_subtitle)
		}
	}

	private fun isBiometricAvailable(): Boolean {
		return packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
	}
}
