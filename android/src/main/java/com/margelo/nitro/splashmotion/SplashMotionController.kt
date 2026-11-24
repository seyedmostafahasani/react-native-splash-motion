package com.margelo.nitro.splashmotion

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
  private const val TIMEOUT_FADE_MS = 220L
  private const val PRELOAD_KEEP_MS = 10_000L
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var visible = false
  @Volatile private var overlayReady = false
  private var splashGone = false

  // Main-thread state
  private var installed = false
  private var overlay: FrameLayout? = null
  private var player: SplashPlayer? = null
  private var window: Window? = null
  private var windowBackground: Drawable? = null
  private var shownAt = 0L
  private var hiding = false
  private val pending = mutableListOf<() -> Unit>()
  private var timeoutMs = 0L

  fun isVisible() = visible

  /** What preload() produced. `composition` is null if the animation could not be parsed. */
  private class Preloaded(val config: SplashConfig, val composition: LottieComposition?)

  private var preloading = false
  private var preloadFinished = false
  private var preloaded: Preloaded? = null
  private var pendingActivity: Activity? = null

  private val dropPreload = Runnable {
    if (installed) return@Runnable
    preloaded = null
    preloadFinished = false
  }

  private val timeout = Runnable {
    if (!visible || hiding) return@Runnable
    Log.w(TAG, "hide() was not called within ${timeoutMs / 1000}s. Removing the splash.")
    if (overlay == null) return@Runnable abort()
    hiding = true
    dismiss(TIMEOUT_FADE_MS)
  }

  // region Showing

  /**
   * Runs at process start (from SplashMotionInitProvider), long before the
   * Activity exists: reads and parses the animation on a background thread, so
   * its first frame is ready the moment the overlay attaches. Main thread only.
   */
  fun preload(context: Context) {
    preloading = true
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
        preloading = false
        preloadFinished = true
        if (!installed) main.postDelayed(dropPreload, PRELOAD_KEEP_MS)
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
    main.removeCallbacks(dropPreload)
    if (!preloading && !preloadFinished) preload(activity.applicationContext)

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
  }

  private fun attachOverlay(activity: Activity) {
    // `visible` is false once hide() or the timeout has already ended the splash.
    if (activity.isDestroyed || !visible) return
    // Before waiting for the preload: the system splash leaves after 1.5s at
    // the latest, and must not reveal the app's own window background.
    tintWindow(activity)
    if (!preloadFinished) {
      pendingActivity = activity
      return
    }
    val loaded = preloaded ?: return abort()
    preloaded = null
    val config = loaded.config
    scheduleTimeout(activity, config.timeoutMs)
    // Application context: the views outlive an Activity recreated during the
    // splash and must not keep the old one alive.
    val context = activity.applicationContext
    val player = LottieSplashPlayer(context, loaded.composition)
    val background = activity.getColor(R.color.splashmotion_background)

    val overlay = FrameLayout(context).apply {
      setBackgroundColor(background)
      isClickable = true // swallow touches while visible
      isFocusable = true
      translationZ = 10_000f
    }
    overlay.addView(player.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    (activity.window.decorView as ViewGroup).addView(
      overlay,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )

    this.overlay = overlay
    this.player = player
    player.load(config, activity.resources.displayMetrics.density) {
      overlayReady = true
      if (splashGone) player.play()
    }
  }

  /** Moves a still-visible overlay to a recreated Activity (e.g. after rotation). */
  private fun moveOverlay(activity: Activity) {
    val overlay = overlay ?: return
    (overlay.parent as? ViewGroup)?.removeView(overlay)
    tintWindow(activity)
    (activity.window.decorView as ViewGroup).addView(
      overlay,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
  }

  /**
   * Safety net: the splash removes itself if JavaScript never calls `hide()`.
   * Release builds only: in development the first Metro bundle can take longer.
   */
  private fun scheduleTimeout(activity: Activity, timeoutMs: Long) {
    val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable || timeoutMs <= 0) return
    this.timeoutMs = timeoutMs
    val elapsed = SystemClock.uptimeMillis() - shownAt
    main.postDelayed(timeout, (timeoutMs - elapsed).coerceAtLeast(0))
  }

  private fun tintWindow(activity: Activity) {
    if (window === activity.window) return
    windowBackground = activity.window.decorView.background
    window = activity.window
    activity.window.setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.splashmotion_background)))
  }

  private fun restoreWindow() {
    window?.setBackgroundDrawable(windowBackground)
    window = null
    windowBackground = null
  }

  /**
   * Lets go of a destroyed Activity. If it is being recreated, the overlay
   * waits detached for moveOverlay(); if it is finishing, the splash is over.
   */
  fun onDestroyed(activity: Activity) {
    val wasPending = pendingActivity === activity
    if (wasPending) pendingActivity = null
    if (window !== activity.window) {
      if (wasPending && activity.isFinishing) abort()
      return
    }
    if (activity.isFinishing) {
      if (overlay != null) finish() else abort()
      return
    }
    overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    window = null
    windowBackground = null
  }

  private fun abort() {
    main.removeCallbacks(timeout)
    restoreWindow()
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
        pendingActivity = null
        abort()
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
    if (fade <= 0) return finish()
    var running = 2
    val done = { if (--running == 0) finish() }
    overlay.animate().alpha(0f).setDuration(fade).withEndAction(done).start()
    player?.fade(fade, done) ?: done()
  }

  /** Removes the overlay and releases everything. Safe to call more than once. */
  private fun finish() {
    main.removeCallbacks(timeout)
    // Before removing its view, or the main thread can wait on a frame in progress.
    player?.stop()
    player = null
    overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    restoreWindow()
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
