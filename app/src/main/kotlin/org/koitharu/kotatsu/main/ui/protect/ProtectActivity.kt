package org.koitharu.kotatsu.main.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityProtectBinding
import javax.inject.Inject
import org.koitharu.kotatsu.core.prefs.AppSettings


@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	View.OnClickListener,
	AuthenticationResultCallback {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<ProtectViewModel>()
	private var canUseBiometric = false
	private var enteredPin = ""
	private var isInputBlocked = false
	private var attemptCount = 0

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityProtectBinding.inflate(layoutInflater))

		setupKeypad()
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonForgot.setOnClickListener(this)

		viewModel.onError.observeEvent(this, this::onError)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.onUnlockSuccess.observeEvent(this) {
			val intent = intent.getParcelableExtraCompat<Intent>(EXTRA_INTENT)
			startActivity(intent)
			finishAfterTransition()
		}

		lifecycleScope.launch {
			withResumed {
				canUseBiometric = useFingerprint()
				updateLeftButton()
			}
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
				if (!isInputBlocked && enteredPin.length < 6) {
					animateTap(btn)
					enteredPin += btn.text
					updateDots()
					if (enteredPin.length == 6) {
						viewModel.tryUnlock(enteredPin)
					}
				}
			}
		}

		viewBinding.btnDelete.setOnClickListener {
			if (!isInputBlocked && enteredPin.isNotEmpty()) {
				animateTap(viewBinding.btnDelete)
				enteredPin = enteredPin.dropLast(1)
				updateDots()
			}
		}

		viewBinding.btnLeft.setOnClickListener {
			if (!isInputBlocked && canUseBiometric) {
				animateTap(viewBinding.btnLeft)
				useFingerprint()
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
			R.id.button_forgot -> showRecoveryDialog()
		}
	}

	override fun onAuthResult(result: AuthenticationResult) {
		if (result.isSuccess()) {
			viewModel.unlock()
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewSubtitle.text = e.getDisplayMessage(resources)
		animateShake(viewBinding.layoutDots)
		enteredPin = ""
		updateDots()

		attemptCount++
		if (attemptCount >= 3) {
			viewBinding.buttonForgot.visibility = View.VISIBLE
		}
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		isInputBlocked = isLoading
	}

	private fun useFingerprint(): Boolean {
		if (!viewModel.isBiometricEnabled) {
			return false
		}
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BIOMETRIC_SUCCESS) {
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			title = getString(R.string.app_name),
			authFallback = Biometric.Fallback.NegativeButton(getString(android.R.string.cancel)),
			init = {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			},
		)
		biometricPrompt.launch(request)
		return true
	}

	private fun updateLeftButton() {
		if (canUseBiometric) {
			viewBinding.btnLeft.setImageResource(R.drawable.ic_fingerprint)
			viewBinding.btnLeft.visibility = View.VISIBLE
		} else {
			viewBinding.btnLeft.visibility = View.INVISIBLE
		}
	}

	private fun showRecoveryDialog() {
		val recoveryWord = settings.appRecoveryWord
		if (recoveryWord.isNullOrEmpty()) {
			com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
				.setTitle(R.string.enter_recovery_word)
				.setMessage(R.string.recovery_word_not_configured)
				.setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
				.show()
			return
		}

		val textInputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
			boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
			hint = getString(R.string.recovery_word_hint)
			val margin = resources.getDimensionPixelSize(R.dimen.screen_padding)
			val lp = android.widget.FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT
			).apply {
				leftMargin = margin
				rightMargin = margin
				topMargin = margin / 2
				bottomMargin = margin / 2
			}
			layoutParams = lp
		}
		val editText = com.google.android.material.textfield.TextInputEditText(textInputLayout.context)
		textInputLayout.addView(editText)

		val container = android.widget.FrameLayout(this).apply {
			addView(textInputLayout)
		}

		com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
			.setTitle(R.string.enter_recovery_word)
			.setView(container)
			.setPositiveButton(R.string.confirm) { dialog, _ ->
				val enteredWord = editText.text?.toString()?.trim() ?: ""
				if (enteredWord.equals(recoveryWord.trim(), ignoreCase = true)) {
					settings.appPassword = null
					android.widget.Toast.makeText(this, R.string.reset_success, android.widget.Toast.LENGTH_LONG).show()
					viewModel.unlock()
				} else {
					android.widget.Toast.makeText(this, R.string.recovery_word_mismatch, android.widget.Toast.LENGTH_SHORT).show()
				}
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel) { dialog, _ ->
				dialog.dismiss()
			}
			.show()
	}

	companion object {
		private const val EXTRA_INTENT = "src_intent"

		fun newIntent(context: Context, sourceIntent: Intent): Intent {
			return Intent(context, ProtectActivity::class.java)
				.putExtra(EXTRA_INTENT, sourceIntent)
		}
	}
}
