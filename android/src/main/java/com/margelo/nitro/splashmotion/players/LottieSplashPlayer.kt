package com.margelo.nitro.splashmotion.players

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.margelo.nitro.splashmotion.SplashConfig
import com.margelo.nitro.splashmotion.SplashPlayer
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** Keep in sync with `maxScreenFraction` (iOS) and the CLI's launch storyboard. */
private const val MAX_SCREEN_FRACTION = 0.9f

/**
 * Draws the splash (background, animation and fade) into its own surface from
 * the render thread. The system compositor shows the surface without the UI
 * thread, so the splash keeps moving while React Native mounts the app on it.
 * The view is created on the main thread; every other call runs on `render`.
 */
internal class LottieSplashPlayer(context: Context, private val render: Handler) : SplashPlayer {
  private val surfaceView = SurfaceView(context).apply {
    setZOrderOnTop(true)
    holder.setFormat(PixelFormat.TRANSLUCENT)
  }
  override val view: View get() = surfaceView

  private val lottie = LottieDrawable().apply {
    // Frames are rendered synchronously: with async updates the first frames
    // after the launch-screen still are drawn empty.
    asyncUpdates = AsyncUpdates.DISABLED
  }
  private val paint = Paint()
  // Guards `surface` between the render thread and surfaceDestroyed (main).
  private val lock = Any()
  private var surface: SurfaceHolder? = null
  private var surfaceWidth = 0
  private var surfaceHeight = 0
  private var animationWidth = 0f
  private var animationHeight = 0f
  private var background = Color.WHITE
  private var alpha = 1f
  private var config: SplashConfig? = null
  private var onFirstFrame: (() -> Unit)? = null
  private var fade: ValueAnimator? = null
  // Lottie invalidates several times per frame; the surface draws once per
  // vsync (more draws block on buffers and miss frames).
  private var drawRequested = false
  private val drawFrame = Choreographer.FrameCallback {
    drawRequested = false
    draw()
  }

  private fun requestDraw() {
    if (drawRequested) return
    drawRequested = true
    // The render thread's own Choreographer.
    Choreographer.getInstance().postFrameCallback(drawFrame)
  }

  // A field: a Drawable holds its callback only through a weak reference.
  private val drawableCallback = object : Drawable.Callback {
    override fun invalidateDrawable(who: Drawable) = requestDraw()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
  }

  init {
    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) = Unit
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
          surface = holder
          surfaceWidth = width
          surfaceHeight = height
        }
        render.post { draw() }
      }
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Returns only once the render thread no longer draws into it.
        synchronized(lock) { surface = null }
      }
    })
    lottie.callback = drawableCallback
  }

  override fun load(
    bytes: ByteArray,
    config: SplashConfig,
    background: Int,
    density: Float,
    onReady: () -> Unit,
  ) {
    // Synchronous on purpose: usually a cache hit thanks to preload().
    val result = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(bytes), CACHE_KEY)
    val composition = result.value
    if (composition == null) {
      Log.e("SplashMotion", "Could not parse the Lottie animation.", result.exception)
      return onReady()
    }
    lottie.setComposition(composition)
    this.config = config
    this.background = background
    animationWidth = (config.width * density).toFloat()
    animationHeight = (config.height * density).toFloat()
    applyLoop()
    intro()?.let { lottie.setMinAndMaxFrame(it) }
    // Hold the first frame (it matches the launch-screen still) until play().
    lottie.frame = lottie.minFrame.toInt()
    // Ready once the first frame is on the surface.
    onFirstFrame = onReady
    draw()
  }

  private fun draw() {
    synchronized(lock) {
      val holder = surface ?: return
      if (surfaceWidth == 0 || surfaceHeight == 0) return
      // Never skip a requested draw: LottieDrawable sends its next invalidate
      // only after its draw() (a skipped draw stops the animation).
      val canvas = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas())
        ?: return
      try {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.color = background
        paint.alpha = (alpha * 255).toInt()
        canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), paint)
        if (lottie.composition != null && animationWidth > 0 && animationHeight > 0) {
          // Configured size, scaled down (keeping the aspect ratio) to fit 90% of the screen.
          val scale = minOf(
            1f,
            surfaceWidth * MAX_SCREEN_FRACTION / animationWidth,
            surfaceHeight * MAX_SCREEN_FRACTION / animationHeight,
          )
          val width = animationWidth * scale
          val height = animationHeight * scale
          canvas.save()
          canvas.translate((surfaceWidth - width) / 2, (surfaceHeight - height) / 2)
          lottie.setBounds(0, 0, width.toInt(), height.toInt())
          // setAlpha invalidates the drawable, which requests another draw:
          // set it only when it changes.
          val drawableAlpha = (alpha * 255).toInt()
          if (lottie.alpha != drawableAlpha) lottie.alpha = drawableAlpha
          lottie.draw(canvas)
          canvas.restore()
        }
      } finally {
        holder.unlockCanvasAndPost(canvas)
      }
    }
    onFirstFrame?.let {
      onFirstFrame = null
      it()
    }
  }

  private fun intro(): String? = config?.introMarker?.takeIf { hasMarker(it) }

  private fun applyLoop() {
    val config = config ?: return
    val marker = config.loopMarker?.takeIf { hasMarker(it) }
    if (marker != null) lottie.setMinAndMaxFrame(marker) else lottie.setMinAndMaxProgress(0f, 1f)
    lottie.repeatCount = if (config.loop) LottieDrawable.INFINITE else 0
  }

  private var started = false

  // Once: both the system splash exit and a late first frame can call it.
  override fun play() {
    if (lottie.composition == null || started) return
    started = true
    val intro = intro() ?: return lottie.playAnimation()
    // A cut-off intro (play() or hide() took over) does not start the loop.
    playMarker(intro, loop = false) { finished ->
      if (finished) {
        applyLoop()
        lottie.playAnimation()
      }
    }
  }

  override fun remainingLoopMs(): Long {
    val composition = lottie.composition ?: return 0
    if (!lottie.isAnimating) return 0
    val framesLeft = (lottie.maxFrame - lottie.frame).coerceAtLeast(0f)
    val speed = abs(lottie.speed).coerceAtLeast(0.01f)
    return (framesLeft / composition.frameRate * 1000f / speed).toLong()
  }

  override fun hasMarker(name: String): Boolean = lottie.composition?.getMarker(name) != null

  /** Reports the marker that is playing now as cut off; set while one plays. */
  private var interrupt: (() -> Unit)? = null

  override fun playMarker(name: String, loop: Boolean, onEnd: (Boolean) -> Unit): Boolean {
    if (!hasMarker(name)) return false

    interrupt?.invoke()
    lottie.removeAllAnimatorListeners()
    lottie.repeatCount = if (loop) LottieDrawable.INFINITE else 0
    lottie.setMinAndMaxFrame(name)
    var ended = false
    val end = { finished: Boolean ->
      if (!ended) {
        ended = true
        interrupt = null
        lottie.removeAllAnimatorListeners()
        onEnd(finished)
      }
    }
    interrupt = { end(false) }
    lottie.addAnimatorListener(object : AnimatorListenerAdapter() {
      override fun onAnimationCancel(animation: Animator) = end(false)
      override fun onAnimationEnd(animation: Animator) = end(true)
    })
    lottie.playAnimation()
    return true
  }

  override fun fade(durationMs: Long, onEnd: () -> Unit) {
    // Android 10+: the compositor fades the surface, so nothing redraws. While
    // the app draws its first heavy frame, a redraw can wait 100 ms for a buffer.
    val control = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) surfaceView.surfaceControl else null
    // One transaction for the whole fade: apply() sends and clears it.
    val transaction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SurfaceControl.Transaction() else null
    val setAlpha = { value: Float ->
      if (control != null && transaction != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (control.isValid) transaction.setAlpha(control, value).apply()
      } else {
        alpha = value
        draw()
      }
    }
    val end = {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) transaction?.close()
      onEnd()
    }
    if (durationMs <= 0) {
      setAlpha(0f)
      return end()
    }
    fade = ValueAnimator.ofFloat(1f, 0f).apply {
      duration = durationMs
      addUpdateListener {
        setAlpha(it.animatedValue as Float)
      }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = end()
      })
      start()
    }
  }

  override fun stop() {
    // A marker still playing ends as cut off, so its play() promise settles.
    interrupt?.invoke()
    fade?.removeAllListeners()
    fade?.cancel()
    lottie.removeAllAnimatorListeners()
    lottie.cancelAnimation()
    lottie.callback = null
  }

  companion object {
    private const val CACHE_KEY = "splashmotion"

    /** Starts parsing in the background; load() then picks the result up from Lottie's cache. */
    fun preload(bytes: ByteArray) {
      LottieCompositionFactory.fromJsonInputStream(ByteArrayInputStream(bytes), CACHE_KEY)
    }
  }
}
