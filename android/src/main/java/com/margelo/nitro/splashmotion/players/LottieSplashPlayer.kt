package com.margelo.nitro.splashmotion.players

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.margelo.nitro.splashmotion.SplashConfig
import com.margelo.nitro.splashmotion.SplashPlayer
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** Keep in sync with `maxScreenFraction` (iOS) and the CLI's launch storyboard. */
private const val MAX_SCREEN_FRACTION = 0.9f

internal class LottieSplashPlayer(
  context: Context,
  /** From [parse]; null if the animation could not be parsed. */
  private val composition: LottieComposition?,
) : SplashPlayer {
  private val main = Handler(Looper.getMainLooper())
  private val thread = HandlerThread("SplashMotion", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
  private val render = Handler(thread.looper)

  private val surfaceView = SurfaceView(context).apply {
    setZOrderOnTop(true)
    holder.setFormat(PixelFormat.TRANSLUCENT)
  }
  override val view: View get() = surfaceView

  private val lock = Any()
  private var surface: SurfaceHolder? = null
  private var surfaceWidth = 0
  private var surfaceHeight = 0

  @Volatile private var stopped = false
  @Volatile private var remainingMs = 0L

  private val lottie = LottieDrawable().apply {
    // Frames are rendered synchronously: with async updates the first frames
    // after the launch-screen still are drawn empty.
    asyncUpdates = AsyncUpdates.DISABLED
    // Lottie needs a View callback to load images and fonts and to read the
    // system's animation settings.
    callback = surfaceView
  }
  private var animationWidth = 0f
  private var animationHeight = 0f
  private var alpha = 1f
  private var onFirstFrame: (() -> Unit)? = null
  private var fadeStart = 0L
  private var fadeDurationNanos = 0L
  private var onFadeEnd: (() -> Unit)? = null
  private var drawRequested = false

  private val drawFrame = Choreographer.FrameCallback { frameTimeNanos ->
    drawRequested = false
    val fadeFinished = stepFade(frameTimeNanos)
    draw()
    updateRemaining()
    // Scheduled here, not from Lottie's invalidations: on API 25 and below it
    // drops the ones sent off the main thread.
    if (lottie.isAnimating) requestDraw()
    if (fadeFinished) {
      onFadeEnd?.let {
        onFadeEnd = null
        main.post(it)
      }
    }
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
        if (!stopped) render.post { draw() }
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { surface = null }
      }
    })
  }

  override fun load(config: SplashConfig, density: Float, onReady: () -> Unit) {
    render.post {
      animationWidth = (config.width * density).toFloat()
      animationHeight = (config.height * density).toFloat()
      composition?.let { composition ->
        lottie.setComposition(composition)
        config.loopMarker?.let { marker ->
          if (composition.getMarker(marker) != null) lottie.setMinAndMaxFrame(marker)
        }
        lottie.repeatCount = if (config.loop) LottieDrawable.INFINITE else 0
        // Hold the first frame (it matches the launch-screen still) until play().
        lottie.frame = lottie.minFrame.toInt()
      }
      onFirstFrame = { main.post(onReady) }
      draw()
    }
  }

  override fun play() {
    render.post {
      if (lottie.composition != null && !lottie.isAnimating) lottie.playAnimation()
      requestDraw()
    }
  }

  override fun remainingLoopMs(): Long = remainingMs

  override fun playOutro(name: String, onEnd: () -> Unit): Boolean {
    composition?.getMarker(name) ?: return false
    render.post {
      lottie.removeAllAnimatorListeners()
      lottie.repeatCount = 0
      lottie.setMinAndMaxFrame(name)
      var finished = false
      lottie.addAnimatorListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (finished) return
          finished = true
          lottie.removeAllAnimatorListeners()
          main.post(onEnd)
        }
      })
      lottie.playAnimation()
      requestDraw()
    }
    return true
  }

  override fun fade(durationMs: Long, onEnd: () -> Unit) {
    render.post {
      fadeStart = 0L
      fadeDurationNanos = durationMs * 1_000_000L
      onFadeEnd = onEnd
      requestDraw()
    }
  }

  override fun stop() {
    stopped = true
    render.post {
      onFirstFrame = null
      onFadeEnd = null
      Choreographer.getInstance().removeFrameCallback(drawFrame)
      lottie.removeAllAnimatorListeners()
      lottie.cancelAnimation()
      lottie.clearComposition()
      thread.quitSafely()
    }
  }

  private fun requestDraw() {
    if (drawRequested) return
    drawRequested = true
    Choreographer.getInstance().postFrameCallback(drawFrame)
  }

  private fun stepFade(frameTimeNanos: Long): Boolean {
    if (onFadeEnd == null) return false
    if (fadeStart == 0L) fadeStart = frameTimeNanos
    val progress =
      if (fadeDurationNanos <= 0) 1f else (frameTimeNanos - fadeStart).toFloat() / fadeDurationNanos
    if (progress >= 1f) {
      alpha = 0f
      return true
    }
    alpha = 1f - progress
    requestDraw()
    return false
  }

  private fun draw() {
    if (stopped) return
    val drawn = synchronized(lock) {
      val holder = surface ?: return@synchronized false
      if (surfaceWidth == 0 || surfaceHeight == 0) return@synchronized false
      val canvas =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas())
          ?: return@synchronized false
      try {
        drawContent(canvas, surfaceWidth.toFloat(), surfaceHeight.toFloat())
      } finally {
        holder.unlockCanvasAndPost(canvas)
      }
      true
    }
    if (drawn) {
      onFirstFrame?.let {
        onFirstFrame = null
        it()
      }
    }
  }

  private fun drawContent(canvas: Canvas, width: Float, height: Float) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val composition = lottie.composition ?: return
    val bounds = composition.bounds
    if (animationWidth <= 0 || animationHeight <= 0 || bounds.isEmpty) return

    val fit = minOf(1f, width * MAX_SCREEN_FRACTION / animationWidth, height * MAX_SCREEN_FRACTION / animationHeight)
    val boxWidth = animationWidth * fit
    val boxHeight = animationHeight * fit
    val scale = minOf(boxWidth / bounds.width(), boxHeight / bounds.height())
    val drawWidth = (bounds.width() * scale).toInt()
    val drawHeight = (bounds.height() * scale).toInt()

    canvas.save()
    canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
    lottie.setBounds(0, 0, drawWidth, drawHeight)
    val alpha255 = (alpha * 255).toInt()
    // setAlpha() always invalidates, even when the value is unchanged.
    if (lottie.alpha != alpha255) lottie.alpha = alpha255
    lottie.draw(canvas)
    canvas.restore()
  }

  private fun updateRemaining() {
    val composition = lottie.composition
    remainingMs = if (composition == null || !lottie.isAnimating) {
      0
    } else {
      val framesLeft = (lottie.maxFrame - lottie.frame).coerceAtLeast(0f)
      val speed = abs(lottie.speed).coerceAtLeast(0.01f)
      (framesLeft / composition.frameRate * 1000f / speed).toLong()
    }
  }

  companion object {
    /**
     * Parses the animation; call it off the main thread. No cache key, so Lottie
     * does not keep the composition in its process-wide cache after the splash.
     */
    fun parse(bytes: ByteArray): LottieComposition? {
      val result = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(bytes), null)
      if (result.value == null) {
        Log.e("SplashMotion", "Could not parse the Lottie animation.", result.exception)
      }
      return result.value
    }
  }
}
