package com.margelo.nitro.splashmotion

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.margelo.nitro.splashmotion.players.LottieSplashPlayer

/**
 * Owns the native overlay. The system splash stays on screen until the
 * overlay's first frame is ready, so the hand-off is seamless.
 *
 * The splash draws, times its steps and fades on its own render thread: the
 * UI thread is busy while React Native starts and mounts the first screen,
 * and the splash must keep moving then. Views are added and removed on main.
 */
internal object SplashMotionController {
  private const val TAG = "SplashMotion"
  private val main = Handler(Looper.getMainLooper())
  // Default priority: a higher one takes the CPU from the app's own startup.
  private val render by lazy { Handler(HandlerThread("SplashMotion").apply { start() }.looper) }

  @Volatile private var visible = false
  @Volatile private var overlayReady = false
  @Volatile private var player: SplashPlayer? = null
  @Volatile private var shownAt = 0L

  // Main-thread state
  private var installed = false
  private var splashGone = false
  private var overlay: FrameLayout? = null
  private var window: Window? = null
  private var windowBackground: Drawable? = null

  // Render-thread state
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
      render.post { player?.let { start(it) } }
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
    val player = LottieSplashPlayer(activity, render)
    val background = activity.getColor(R.color.splashmotion_background)

    // The post-splash theme's window background (black with DayNight in dark
    // mode) would show for a frame if the system splash leaves before the
    // overlay draws. Use the splash color until the overlay is gone.
    windowBackground = activity.window.decorView.background
    window = activity.window
    activity.window.setBackgroundDrawable(ColorDrawable(background))

    val density = activity.resources.displayMetrics.density
    // Transparent: the player's surface draws the background, so its fade
    // reveals the app without the UI thread.
    val overlay = FrameLayout(activity).apply {
      isClickable = true // swallow touches while visible
      isFocusable = true
      translationZ = 10_000f
    }
    overlay.addView(
      player.view,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    (activity.window.decorView as ViewGroup).addView(
      overlay,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )

    this.overlay = overlay
    this.player = player
    render.post {
      player.load(bytes, config, background, density) {
        overlayReady = true
        // The system splash left early (the safety net): start now.
        main.post { if (splashGone) render.post { start(player) } }
      }
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

  // play() and hide() steps run one after another, in call order, and only
  // after the animation has started: a step never races the start.
  private val steps = ArrayDeque<(done: () -> Unit) -> Unit>()
  private var stepRunning = false
  private var started = false

  /** Starts the animation (once) when the system splash is gone, then the steps. */
  private fun start(player: SplashPlayer) {
    if (started) return
    started = true
    player.play()
    runNextStep()
  }

  private fun enqueue(step: (done: () -> Unit) -> Unit) {
    steps.addLast(step)
    runNextStep()
  }

  private fun runNextStep() {
    if (stepRunning || !started) return
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
      render.postDelayed({ body() }, player.remainingLoopMs().coerceIn(0, 5_000))
    } else {
      body()
    }
  }

  // endregion

  // region Playing a marker

  fun play(marker: String, waitForLoop: Boolean, loop: Boolean, done: (Boolean) -> Unit) {
    render.post {
      val player = this.player
      if (hiding || player == null) {
        done(false)
        return@post
      }
      enqueue { next ->
        // Checked here: the animation is loaded once the steps run.
        if (!player.hasMarker(marker)) {
          done(false)
          return@enqueue next()
        }
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
    render.post {
      if (player == null) {
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
        render.postDelayed(
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

  // The fade draws on the render thread; only the removal of the (by then
  // transparent) overlay waits for the UI thread.
  private fun dismiss(fade: Long) {
    val player = this.player ?: return flush()
    player.fade(fade) {
      main.post {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        window?.setBackgroundDrawable(windowBackground)
        window = null
        windowBackground = null
        render.post {
          player.stop()
          this.player = null
          visible = false
          flush()
        }
      }
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

