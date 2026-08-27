package com.nudge.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nudge.app.BuildConfig

class OverlayManager(private val context: Context) {

    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    fun isShowing(): Boolean = overlayView != null

    @SuppressLint("SetTextI18n")
    fun show(
        appName: String,
        appIcon: Drawable?,
        minutesUsed: Long,
        onTakeBreak: () -> Unit = {},
        onSnooze: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        if (!canDrawOverlays(context)) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Overlay permission (SYSTEM_ALERT_WINDOW) not granted!")
            }
            return
        }

        mainHandler.post {
            // Dismiss existing overlay if any
            dismissImmediate()

            val wm = windowManager
            if (wm == null) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Could not obtain WindowManager")
                }
                return@post
            }

            // Wrap context with DeviceDefault theme so standard widgets have styling
            val themedContext = ContextThemeWrapper(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            )

            val wmParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val density = context.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            // Root overlay container
            val rootLayout = LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
            }

            // Card container
            val cardView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24f), dp(24f), dp(24f), dp(20f))

                // Card background with rounded corners and subtle border
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#14231F")) // Deep calming wellbeing surface
                    cornerRadius = dp(24f).toFloat()
                    setStroke(dp(1.5f), Color.parseColor("#00796B")) // Teal accent border
                }
                elevation = dp(12f).toFloat()
            }

            // Header Icon / Emoji
            if (appIcon != null) {
                val iconView = ImageView(themedContext).apply {
                    setImageDrawable(appIcon)
                    val iconLp = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply {
                        bottomMargin = dp(12f)
                    }
                    this.layoutParams = iconLp
                }
                cardView.addView(iconView)
            } else {
                val emojiView = TextView(themedContext).apply {
                    text = "\uD83C\uDF3F"
                    textSize = 36f
                    gravity = Gravity.CENTER
                    val emojiLp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8f)
                    }
                    this.layoutParams = emojiLp
                }
                cardView.addView(emojiView)
            }

            // Title
            val titleView = TextView(themedContext).apply {
                text = "Time for a breather?"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#EBF2EF"))
                gravity = Gravity.CENTER
                val titleLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8f)
                }
                this.layoutParams = titleLp
            }
            cardView.addView(titleView)

            // Subtitle
            val subtitleView = TextView(themedContext).apply {
                text = "You've been using $appName for $minutesUsed minutes continuously."
                textSize = 14f
                setTextColor(Color.parseColor("#B0C4BE"))
                gravity = Gravity.CENTER
                val subLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(20f)
                }
                this.layoutParams = subLp
            }
            cardView.addView(subtitleView)

            // Action Buttons Row
            val buttonRow = LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val btnRowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                this.layoutParams = btnRowLp
            }

            // Button 1: "Take a Break" (Primary filled button -> sends to Home screen)
            val breakButton = Button(themedContext).apply {
                text = "Take a Break \uD83D\uDE4C"
                setTextColor(Color.parseColor("#0F1A17"))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#A0D2DB")) // Primary teal accent
                    cornerRadius = dp(14f).toFloat()
                }
                val breakLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48f)
                ).apply {
                    bottomMargin = dp(8f)
                }
                this.layoutParams = breakLp
                setOnClickListener {
                    dismissImmediate()
                    onTakeBreak()
                    // Send to device home screen
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(homeIntent)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) {
                            Log.e(TAG, "Failed to launch home intent: ${e.message}")
                        }
                    }
                }
            }
            buttonRow.addView(breakButton)

            // Secondary Buttons Row (Snooze + Dismiss)
            val secondaryRow = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val secRowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                this.layoutParams = secRowLp
            }

            // Button 2: "+5m Snooze"
            val snoozeButton = Button(themedContext).apply {
                text = "+5m Snooze"
                setTextColor(Color.parseColor("#A0D2DB"))
                textSize = 13f
                setTypeface(null, Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A2B25"))
                    cornerRadius = dp(12f).toFloat()
                    setStroke(dp(1f), Color.parseColor("#00796B"))
                }
                val snoozeLp = LinearLayout.LayoutParams(
                    0,
                    dp(42f),
                    1f
                ).apply {
                    marginEnd = dp(6f)
                }
                this.layoutParams = snoozeLp
                setOnClickListener {
                    dismissImmediate()
                    onSnooze()
                }
            }
            secondaryRow.addView(snoozeButton)

            // Button 3: "Dismiss"
            val dismissButton = Button(themedContext).apply {
                text = "Dismiss"
                setTextColor(Color.parseColor("#8E9D97"))
                textSize = 13f
                setTypeface(null, Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(12f).toFloat()
                }
                val dismissLp = LinearLayout.LayoutParams(
                    0,
                    dp(42f),
                    1f
                ).apply {
                    marginStart = dp(6f)
                }
                this.layoutParams = dismissLp
                setOnClickListener {
                    dismissImmediate()
                    onDismiss()
                }
            }
            secondaryRow.addView(dismissButton)

            buttonRow.addView(secondaryRow)
            cardView.addView(buttonRow)
            rootLayout.addView(cardView)

            try {
                wm.addView(rootLayout, wmParams)
                overlayView = rootLayout
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Overlay window successfully added to WindowManager for $appName")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Exception adding view to WindowManager: ${e.message}", e)
                }
                overlayView = null
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            dismissImmediate()
        }
    }

    private fun dismissImmediate() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Overlay window removed from WindowManager")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Exception removing overlay view: ${e.message}", e)
                }
            }
            overlayView = null
        }
    }

    companion object {
        private const val TAG = "ScreenTimeTracker"

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }
}
