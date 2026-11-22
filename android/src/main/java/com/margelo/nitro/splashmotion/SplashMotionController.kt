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
import com.airbnb.lottie.LottieComposition
import com.margelo.nitro.splashmotion.players.LottieSplashPlayer

/**
 * Owns the native overlay. The system splash stays on screen until the
 * overlay's first frame is ready, so the hand-off is seamless.
 */
internal object SplashMotionController {
  private const val TAG = "SplashMotion"
  /** Safety net: the splash removes itself if JavaScript never calls `hide()`. */
  private const val MAX_VISIBLE_MS = 15_000L
  private const val TIMEOUT_FADE_MS = 220L
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

  /** What preload() produced. `composition` is null if the animation could not be parsed. */
  private class Preloaded(val config: SplashConfig, val composition: LottieComposition?)

  // Main-thread state, filled in by the preload thread through `main`.
  private var preloadFinished = false
  private var preloaded: Preloaded? = null
  // The Activity whose overlay is waiting for the preload to finish.
  private var pendingActivity: Activity? = null

  private val timeout = Runnable {
    if (!visible || hiding) return@Runnable
    Log.w(TAG, "hide() was not called within ${MAX_VISIBLE_MS / 1000}s. Removing the splash.")
    if (overlay == null) return@Runnable abort()
    hiding = true
    dismiss(TIMEOUT_FADE_MS)
  }

  // region Showing

  /**
   * Runs at process start (from SplashMotionInitProvider), long before the
   * Activity exists: reads and parses the animation on a background thread, so
   * its first frame is ready the moment the overlay attaches.
   */
  fun preload(context: Context) {
    Thread({
      val result = try {
        readAndParse(context)
      } catch (e: Exception) {
        Log.e(TAG, "Could not preload the animation.", e)
        null
      }
      // Ahead of React Native's startup work already queued on the main thread.
      main.postAtFrontOfQueue {
        preloaded = result
        preloadFinished = true
        pendingActivity?.let {
          pendingActivity = null
          attachOverlay(it)
        }
      }
    }, "SplashMotionPreload").start()
  }

  private fun readAndParse(context: Context): Preloaded? {
    val config = SplashConfig.load(context)
    if (config == null) {
      Log.w(TAG, "No config found. Run `npx splash-motion generate <file>` and rebuild.")
      return null
    }
    val bytes = readAnimation(context, config) ?: return null
    return Preloaded(config, LottieSplashPlayer.parse(bytes))
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
      main.post {
        if (overlay != null) moveOverlay(activity) else if (visible) attachOverlay(activity)
      }
      return
    }
    installed = true

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
    main.post { attachOverlay(activity) }
    // Safety net: never block the launch if the animation fails to load.
    main.postDelayed({ overlayReady = true }, 1500)
    main.postDelayed(timeout, MAX_VISIBLE_MS)
  }

  private fun attachOverlay(activity: Activity) {
    if (activity.isDestroyed) return
    if (!preloadFinished) {
      pendingActivity = activity
      return
    }
    val loaded = preloaded ?: return abort()
    preloaded = null
    val config = loaded.config
    // Application context: the views outlive an Activity recreated during the
    // splash and must not keep the old one alive.
    val context = activity.applicationContext
    val player = LottieSplashPlayer(context, loaded.composition)
    val background = activity.getColor(R.color.splashmotion_background)

    // The post-splash theme's window background (black with DayNight in dark
    // mode) would show for a frame if the system splash leaves before the
    // overlay draws. Use the splash color until the overlay is gone.
    windowBackground = activity.window.decorView.background
    window = activity.window
    activity.window.setBackgroundDrawable(ColorDrawable(background))

    val density = activity.resources.displayMetrics.density
    val overlay = SplashOverlay(context, (config.width * density).toFloat(), (config.height * density).toFloat()).apply {
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
    player.load(config) {
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

  /**
   * Lets go of a destroyed Activity. If it is being recreated, the overlay
   * waits detached for moveOverlay(); if it is finishing, the splash is over.
   */
  fun onDestroyed(activity: Activity) {
    if (pendingActivity === activity) {
      pendingActivity = null
      if (activity.isFinishing) abort()
      return
    }
    val overlay = overlay ?: return
    if (window !== activity.window) return
    if (activity.isFinishing) {
      finish()
    } else {
      (overlay.parent as? ViewGroup)?.removeView(overlay)
      window = null
      windowBackground = null
    }
  }

  private fun abort() {
    main.removeCallbacks(timeout)
    overlayReady = true
    visible = false
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

      val elapsed = SystemClock.uptimeMillis() - shownAt
      main.postDelayed(
        { runExit(fade, waitForLoop, outro) },
        (minDuration - elapsed).coerceAtLeast(0),
      )
    }
  }

  private fun runExit(fade: Long, waitForLoop: Boolean, outro: String?) {
    val player = this.player ?: return dismiss(fade)
    val afterLoop = Runnable {
      val playing = outro != null && player.playOutro(outro) { dismiss(fade) }
      if (!playing) dismiss(fade)
    }
    if (waitForLoop) {
      main.postDelayed(afterLoop, player.remainingLoopMs().coerceIn(0, 5_000))
    } else {
      afterLoop.run()
    }
  }

  private fun dismiss(fade: Long) {
    val overlay = this.overlay ?: return flush()
    if (fade > 0) {
      overlay.animate().alpha(0f).setDuration(fade).withEndAction { finish() }.start()
    } else {
      finish()
    }
  }

  /** Removes the overlay and releases everything. Safe to call more than once. */
  private fun finish() {
    main.removeCallbacks(timeout)
    overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    window?.setBackgroundDrawable(windowBackground)
    window = null
    windowBackground = null
    player?.stop()
    player = null
    overlay = null
    visible = false
    flush()
  }

  private fun flush() {
    val callbacks = pending.toList()
    pending.clear()
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
