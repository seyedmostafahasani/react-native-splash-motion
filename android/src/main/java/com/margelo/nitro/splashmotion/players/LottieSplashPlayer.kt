package com.margelo.nitro.splashmotion.players

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.Log
import android.view.View
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.margelo.nitro.splashmotion.SplashConfig
import com.margelo.nitro.splashmotion.SplashPlayer
import java.io.ByteArrayInputStream
import kotlin.math.abs

internal class LottieSplashPlayer(context: Context) : SplashPlayer {
  // Frames are rendered synchronously: with async updates the first frames
  // after the launch-screen still are drawn empty.
  private val lottie = LottieAnimationView(context).apply {
    asyncUpdates = AsyncUpdates.DISABLED
  }

  override val view: View get() = lottie

  override fun load(bytes: ByteArray, config: SplashConfig, onReady: () -> Unit) {
    // Synchronous on purpose: usually a cache hit thanks to preload(). The async
    // API would deliver the result through the main thread's queue, behind
    // React Native's startup work, leaving the overlay empty for a while.
    val result = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(bytes), CACHE_KEY)
    val composition = result.value
    if (composition == null) {
      Log.e("SplashMotion", "Could not parse the Lottie animation.", result.exception)
      return onReady()
    }
    lottie.setComposition(composition)
    this.config = config
    applyLoop()
    intro()?.let { lottie.setMinAndMaxFrame(it) }
    // Hold the first frame (it matches the launch-screen still) until play().
    lottie.frame = lottie.minFrame.toInt()
    onReady()
  }

  private var config: SplashConfig? = null

  private fun intro(): String? = config?.introMarker?.takeIf { hasMarker(it) }

  private fun applyLoop() {
    val config = config ?: return
    val marker = config.loopMarker?.takeIf { hasMarker(it) }
    if (marker != null) lottie.setMinAndMaxFrame(marker) else lottie.setMinAndMaxProgress(0f, 1f)
    lottie.repeatCount = if (config.loop) LottieDrawable.INFINITE else 0
  }

  override fun play() {
    if (lottie.composition == null || lottie.isAnimating) return
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

  override fun stop() {
    // A marker still playing ends as cut off, so its play() promise settles.
    interrupt?.invoke()
    lottie.removeAllAnimatorListeners()
    lottie.cancelAnimation()
  }

  companion object {
    private const val CACHE_KEY = "splashmotion"

    /** Starts parsing in the background; load() then picks the result up from Lottie's cache. */
    fun preload(bytes: ByteArray) {
      LottieCompositionFactory.fromJsonInputStream(ByteArrayInputStream(bytes), CACHE_KEY)
    }
  }
}
