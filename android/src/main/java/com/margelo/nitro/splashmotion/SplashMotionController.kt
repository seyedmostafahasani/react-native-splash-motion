package com.margelo.nitro.splashmotion

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.margelo.nitro.splashmotion.players.LottieSplashPlayer

/**
 * Owns the native overlay. The system splash stays on screen until the
 * overlay's first frame is ready, so the hand-off is seamless.
 */
internal object SplashMotionController {
  private const val TAG = "SplashMotion"
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var visible = false
  @Volatile private var overlayReady = false
  private var splashGone = false

  // Main-thread state
  private var installed = false
  private var overlay: SplashOverlay? = null
  private var player: SplashPlayer? = null
  private var window: Window? = null
  private var windowBackground: Drawable? = null
  private var shownAt = 0L
  private var hiding = false
  private val pending = mutableListOf<() -> Unit>()

  fun isVisible() = visible

  // Read at process start by preload(), consumed by the first install().
  private var preloadedConfig: SplashConfig? = null
  private var preloadedBytes: ByteArray? = null

  // region Showing

  /**
   * Runs at process start (from SplashMotionInitProvider), long before the
   * Activity exists: reads the animation and starts parsing it in the
   * background, so its first frame is ready the moment the overlay attaches.
   */
  fun preload(context: Context) {
    val config = SplashConfig.load(context) ?: return
    val bytes = readAnimation(context, config) ?: return
    preloadedConfig = config
    preloadedBytes = bytes
    LottieSplashPlayer.preload(bytes)
  }

  private fun readAnimation(context: Context, config: SplashConfig): ByteArray? = try {
    context.assets.open("splashmotion/${config.file}").use { it.readBytes() }
  } catch (e: Exception) {
    Log.e(TAG, "Could not read the animation file.", e)
    null
  }

  fun install(activity: Activity) {
    // Every ReactActivity starts on the splash theme, so each one must install the
    // splash screen to switch to its post-splash theme; skipping it crashes
    // AppCompat ("You need to use a Theme.AppCompat theme").
    val splash = activity.installSplashScreen()
    if (installed) {
      // A later Activity in the same process: reopened after Back (React Native is
      // already running, so no animation) or recreated while the splash is up.
      main.post { moveOverlay(activity) }
      return
    }
    installed = true

    val config = preloadedConfig ?: SplashConfig.load(activity)
    if (config == null) {
      Log.w(TAG, "No config found. Run `npx splash-motion generate <file>` and rebuild.")
      return
    }

    visible = true
    shownAt = SystemClock.uptimeMillis()
    splash.setKeepOnScreenCondition { !overlayReady }
    // Start the animation only once it's visible, so it picks up exactly
    // where the launch-screen still leaves off.
    splash.setOnExitAnimationListener {
      it.remove()
      splashGone = true
      player?.play()
    }

    // Runs right after onCreate(), once React Native has set its content view.
    main.post { attachOverlay(activity, config) }
    // Safety net: never block the launch if the animation fails to load.
    main.postDelayed({ overlayReady = true }, 1500)
  }

  private fun attachOverlay(activity: Activity, config: SplashConfig) {
    val bytes = preloadedBytes ?: readAnimation(activity, config) ?: return abort()
    preloadedConfig = null
    preloadedBytes = null
    val player = LottieSplashPlayer(activity)
    val background = activity.getColor(R.color.splashmotion_background)

    // The post-splash theme's window background (black with DayNight in dark
    // mode) would show for a frame if the system splash leaves before the
    // overlay draws. Use the splash color until the overlay is gone.
    windowBackground = activity.window.decorView.background
    window = activity.window
    activity.window.setBackgroundDrawable(ColorDrawable(background))

    val density = activity.resources.displayMetrics.density
    val overlay = SplashOverlay(activity, (config.width * density).toFloat(), (config.height * density).toFloat()).apply {
      setBackgroundColor(background)
      isClickable = true // swallow touches while visible
      isFocusable = true
      translationZ = 10_000f
    }
    overlay.addView(player.view, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
    (activity.window.decorView as ViewGroup).addView(
      overlay,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )

    this.overlay = overlay
    this.player = player
    player.load(bytes, config) {
      overlayReady = true
      if (splashGone) player.play()
    }
  }

  /** Moves a still-visible overlay to a recreated Activity (e.g. after rotation). */
  private fun moveOverlay(activity: Activity) {
    val overlay = overlay ?: return
    (overlay.parent as? ViewGroup)?.removeView(overlay)
    windowBackground = activity.window.decorView.background
    window = activity.window
    activity.window.setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.splashmotion_background)))
    (activity.window.decorView as ViewGroup).addView(
      overlay,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
  }

  private fun abort() {
    overlayReady = true
    visible = false
  }

  // endregion

  // region Steps

  // play() and hide() steps run one after another, in call order.
  private val steps = ArrayDeque<(done: () -> Unit) -> Unit>()
  private var stepRunning = false

  private fun enqueue(step: (done: () -> Unit) -> Unit) {
    steps.addLast(step)
    runNextStep()
  }

  private fun runNextStep() {
    if (stepRunning) return
    val step = steps.removeFirstOrNull() ?: return
    stepRunning = true
    step {
      stepRunning = false
      runNextStep()
    }
  }

  /** Runs `body` once the current loop or marker ends (at most 5 s), or now. */
  private fun afterCurrentLoop(waitForLoop: Boolean, player: SplashPlayer, body: () -> Unit) {
    if (waitForLoop) {
      main.postDelayed({ body() }, player.remainingLoopMs().coerceIn(0, 5_000))
    } else {
      body()
    }
  }

  // endregion

  // region Playing a marker

  fun play(marker: String, waitForLoop: Boolean, loop: Boolean, done: (Boolean) -> Unit) {
    main.post {
      val player = this.player
      if (hiding || player == null || !player.hasMarker(marker)) {
        done(false)
        return@post
      }
      enqueue { next ->
        afterCurrentLoop(waitForLoop, player) {
          player.playMarker(marker, loop) { finished -> if (!loop) done(finished) }
          if (loop) done(true)
          next()
        }
      }
    }
  }

  // endregion

  // region Hiding

  fun hide(
    fade: Long,
    minDuration: Long,
    waitForLoop: Boolean,
    outro: String?,
    done: () -> Unit,
  ) {
    main.post {
      if (overlay == null) {
        visible = false
        done()
        return@post
      }
      pending += done
      if (hiding) return@post
      hiding = true

      // The last step: it ends with the overlay, so it never calls `next`.
      enqueue {
        val elapsed = SystemClock.uptimeMillis() - shownAt
        main.postDelayed(
          { runExit(fade, waitForLoop, outro) },
          (minDuration - elapsed).coerceAtLeast(0),
        )
      }
    }
  }

  private fun runExit(fade: Long, waitForLoop: Boolean, outro: String?) {
    val player = this.player ?: return dismiss(fade)
    afterCurrentLoop(waitForLoop, player) {
      val playing = outro != null && player.playMarker(outro, loop = false) { dismiss(fade) }
      if (!playing) dismiss(fade)
    }
  }

  private fun dismiss(fade: Long) {
    val overlay = this.overlay ?: return flush()
    val finish = Runnable {
      (overlay.parent as? ViewGroup)?.removeView(overlay)
      window?.setBackgroundDrawable(windowBackground)
      window = null
      windowBackground = null
      player?.stop()
      player = null
      this.overlay = null
      visible = false
      flush()
    }
    if (fade > 0) {
      overlay.animate().alpha(0f).setDuration(fade).withEndAction(finish).start()
    } else {
      finish.run()
    }
  }

  private fun flush() {
    val callbacks = pending.toList()
    pending.clear()
    steps.clear()
    stepRunning = false
    hiding = false
    callbacks.forEach { it() }
  }

  // endregion
}

/** Keep in sync with `maxScreenFraction` (iOS) and the CLI's launch storyboard. */
private const val MAX_SCREEN_FRACTION = 0.9f

/**
 * Centers the animation at its configured size, scaled down (keeping the aspect
 * ratio) to fit 90% of the overlay. Re-measured on rotation and resize.
 */
internal class SplashOverlay(
  context: Context,
  private val widthPx: Float,
  private val heightPx: Float,
) : FrameLayout(context) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val maxWidth = MeasureSpec.getSize(widthMeasureSpec) * MAX_SCREEN_FRACTION
    val maxHeight = MeasureSpec.getSize(heightMeasureSpec) * MAX_SCREEN_FRACTION
    var scale = 1f
    if (maxWidth > 0) scale = minOf(scale, maxWidth / widthPx)
    if (maxHeight > 0) scale = minOf(scale, maxHeight / heightPx)
    getChildAt(0)?.layoutParams?.let {
      it.width = (widthPx * scale).toInt()
      it.height = (heightPx * scale).toInt()
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }
}
