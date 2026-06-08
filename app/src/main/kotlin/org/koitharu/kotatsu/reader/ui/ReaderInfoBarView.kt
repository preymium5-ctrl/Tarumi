package org.koitharu.kotatsu.reader.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.util.AttributeSet
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withScale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.isNightMode
import org.koitharu.kotatsu.core.util.ext.measureDimension
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import com.google.android.material.R as materialR

private const val ALPHA_TEXT = 230
private const val ALPHA_BG = 180

class ReaderInfoBarView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
	private val textBounds = Rect()
	private val pillRect = RectF()
	private val systemStateReceiver = SystemStateReceiver()
	private var insetLeft: Int = 0
	private var insetRight: Int = 0
	private var insetTop: Int = 0
	private val insetLeftFallback: Int
	private val insetRightFallback: Int
	private val insetTopFallback: Int
	private val insetCornerFallback = getSystemUiDimensionOffset("rounded_corner_content_padding")
	private var colorText =
		(context.getThemeColorStateList(materialR.attr.colorOnSurface)
			?: ColorStateList.valueOf(Color.BLACK)).withAlpha(ALPHA_TEXT)
	private var colorBackground =
		(context.getThemeColorStateList(materialR.attr.colorSurface)
			?: ColorStateList.valueOf(Color.WHITE)).withAlpha(ALPHA_BG)
	private val batteryIcon = ContextCompat.getDrawable(context, R.drawable.ic_battery_outline)

	private var currentTextColor: Int = Color.TRANSPARENT
	private var currentBackgroundColor: Int = Color.TRANSPARENT
	private var currentOutlineColor: Int = Color.TRANSPARENT
	private var batteryText = ""
	private var text: String = ""
	private var prevTextHeight: Int = 0

	private val innerHeight
		get() = height - paddingTop - paddingBottom - insetTop

	private val innerWidth
		get() = width - paddingLeft - paddingRight - insetLeft - insetRight

	var drawBackground: Boolean = false
		set(value) {
			field = value
			invalidate()
		}

	var isTimeVisible: Boolean = true
		set(value) {
			field = value
			invalidate()
		}

	init {
		context.withStyledAttributes(attrs, R.styleable.ReaderInfoBarView, defStyleAttr) {
			paint.strokeWidth = getDimension(R.styleable.ReaderInfoBarView_android_strokeWidth, 2f)
			paint.textSize = getDimension(R.styleable.ReaderInfoBarView_android_textSize, 12f)
		}
		val insetStart = getSystemUiDimensionOffset("status_bar_padding_start").coerceAtLeast(0)
		val insetEnd = getSystemUiDimensionOffset("status_bar_padding_end").coerceAtLeast(0)
		val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
		insetLeftFallback = if (isRtl) insetEnd else insetStart
		insetRightFallback = if (isRtl) insetStart else insetEnd
		insetTopFallback = minOf(insetLeftFallback, insetRightFallback)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val desiredWidth = suggestedMinimumWidth + paddingLeft + paddingRight + insetLeft + insetRight
		val desiredHeight = maxOf(
			computeTextHeight().also { prevTextHeight = it },
			suggestedMinimumHeight,
		) + paddingTop + paddingBottom + insetTop
		setMeasuredDimension(
			measureDimension(desiredWidth, widthMeasureSpec),
			measureDimension(desiredHeight, heightMeasureSpec),
		)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		computeTextHeight()
		if (text.isEmpty()) {
			return
		}
		val horizontalPadding = 10f * resources.displayMetrics.density
		val verticalPadding = 4f * resources.displayMetrics.density
		val textWidth = paint.measureText(text)
		val pillWidth = textWidth + horizontalPadding * 2f
		val pillHeight = textBounds.height() + verticalPadding * 2f
		val left = (width - pillWidth) / 2f
		val top = paddingTop + insetTop + ((innerHeight - pillHeight) / 2f).coerceAtLeast(0f)
		pillRect.set(left, top, left + pillWidth, top + pillHeight)

		paint.style = Paint.Style.FILL
		paint.color = Color.argb(205, 16, 16, 18)
		canvas.drawRoundRect(pillRect, pillHeight / 3f, pillHeight / 3f, paint)

		paint.textAlign = Paint.Align.CENTER
		paint.color = Color.WHITE
		val ty = pillRect.centerY() + textBounds.height() / 2f - textBounds.bottom
		canvas.drawText(text, pillRect.centerX(), ty, paint)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		updateCutoutInsets(ViewCompat.getRootWindowInsets(this))
	}

	override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
		updateCutoutInsets(WindowInsetsCompat.toWindowInsetsCompat(insets))
		return super.onApplyWindowInsets(insets)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		ContextCompat.registerReceiver(
			context,
			systemStateReceiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_TIME_TICK)
				addAction(Intent.ACTION_BATTERY_CHANGED)
			},
			ContextCompat.RECEIVER_EXPORTED,
		)
		updateCutoutInsets(ViewCompat.getRootWindowInsets(this))
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		context.unregisterReceiver(systemStateReceiver)
	}

	override fun verifyDrawable(who: Drawable): Boolean {
		return who == batteryIcon || super.verifyDrawable(who)
	}

	override fun jumpDrawablesToCurrentState() {
		super.jumpDrawablesToCurrentState()
		batteryIcon?.jumpToCurrentState()
	}

	override fun onCreateDrawableState(extraSpace: Int): IntArray? {
		val iconState = batteryIcon?.state ?: return super.onCreateDrawableState(extraSpace)
		return mergeDrawableStates(super.onCreateDrawableState(extraSpace + iconState.size), iconState)
	}

	override fun drawableStateChanged() {
		currentTextColor = colorText.getColorForState(drawableState, colorText.defaultColor)
		currentBackgroundColor = colorBackground.getColorForState(drawableState, colorBackground.defaultColor)
		currentOutlineColor = ColorUtils.setAlphaComponent(currentBackgroundColor, Color.alpha(currentTextColor))
		super.drawableStateChanged()
		if (batteryIcon != null && batteryIcon.isStateful && batteryIcon.setState(drawableState)) {
			invalidateDrawable(batteryIcon)
		}
	}

	fun applyColorScheme(isBlackOnWhite: Boolean) {
		val isDarkTheme = resources.isNightMode
		colorText = (context.getThemeColorStateList(
			if (isBlackOnWhite != isDarkTheme) materialR.attr.colorOnSurface else materialR.attr.colorOnSurfaceInverse,
		) ?: ColorStateList.valueOf(if (isBlackOnWhite) Color.BLACK else Color.WHITE)).withAlpha(ALPHA_TEXT)
		colorBackground = (context.getThemeColorStateList(
			if (isBlackOnWhite != isDarkTheme) materialR.attr.colorSurface else materialR.attr.colorSurfaceInverse,
		) ?: ColorStateList.valueOf(if (isBlackOnWhite) Color.WHITE else Color.BLACK)).withAlpha(ALPHA_BG)
		batteryIcon?.setTintList(colorText)
		drawableStateChanged()
	}

	@SuppressLint("StringFormatMatches")
	fun update(state: ReaderUiState?) {
		text = if (state != null) {
			val percent = (state.percent * 100f).toInt().coerceIn(0, 100)
			val chapter = state.chapterNumber.coerceAtLeast(1)
			val chaptersTotal = state.chaptersTotal.coerceAtLeast(chapter)
			"$percent% - $chapter/$chaptersTotal chapters"
		} else {
			""
		}
		val newHeight = computeTextHeight()
		if (newHeight != prevTextHeight) {
			prevTextHeight = newHeight
			requestLayout()
		}
		invalidate()
	}

	private fun computeTextHeight(): Int {
		val str = text + batteryText
		paint.getTextBounds(str, 0, str.length, textBounds)
		return textBounds.height()
	}

	private fun updateCutoutInsets(insetsCompat: WindowInsetsCompat?) {
		insetLeft = insetLeftFallback
		insetRight = insetRightFallback
		insetTop = insetTopFallback
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && insetsCompat != null) {
			val nativeInsets = insetsCompat.toWindowInsets()
			nativeInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { corner ->
				insetLeft += corner.radius
			}
			nativeInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.let { corner ->
				insetRight += corner.radius
			}
		} else {
			insetLeft += insetCornerFallback
			insetRight += insetCornerFallback
		}
		insetsCompat?.displayCutout?.let { cutout ->
			for (rect in cutout.boundingRects) {
				if (rect.left <= paddingLeft) {
					insetLeft += rect.width()
				}
				if (rect.right >= width - paddingRight) {
					insetRight += rect.width()
				}
			}
		}
	}

	private fun Canvas.drawTextOutline(text: String, x: Float, y: Float) {
		paint.color = currentOutlineColor
		paint.style = Paint.Style.STROKE
		drawText(text, x, y, paint)
		paint.color = currentTextColor
		paint.style = Paint.Style.FILL
		drawText(text, x, y, paint)
	}

	private fun Drawable.drawWithOutline(canvas: Canvas) {
		if (bounds.isEmpty) {
			return
		}
		var requiredScale = (bounds.width() + paint.strokeWidth * 2f) / bounds.width().toFloat()
		setTint(currentOutlineColor)
		canvas.withScale(requiredScale, requiredScale, bounds.exactCenterX(), bounds.exactCenterY()) {
			draw(canvas)
		}
		requiredScale = 1f / requiredScale
		canvas.withScale(requiredScale, requiredScale, bounds.exactCenterX(), bounds.exactCenterY()) {
			draw(canvas)
		}
		setTint(currentTextColor)
		draw(canvas)
	}

	private inner class SystemStateReceiver : BroadcastReceiver() {

		override fun onReceive(context: Context, intent: Intent) {
			val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
			val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
			if (level != -1 && scale != -1) {
				batteryText = context.getString(R.string.percent_string_pattern, (level * 100 / scale).toString())
			}

			if (isTimeVisible) {
				invalidate()
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	private fun getSystemUiDimensionOffset(name: String, fallback: Int = 0): Int = runCatching {
		val manager = context.packageManager
		val resources = manager.getResourcesForApplication("com.android.systemui")
		val resId = resources.getIdentifier(name, "dimen", "com.android.systemui")
		resources.getDimensionPixelOffset(resId)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(fallback)
}
