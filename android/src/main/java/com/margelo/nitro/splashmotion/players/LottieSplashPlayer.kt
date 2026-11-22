package com.margelo.nitro.splashmotion.players

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.Log
import android.view.View
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.margelo.nitro.splashmotion.SplashConfig
import com.margelo.nitro.splashmotion.SplashPlayer
import java.io.ByteArrayInputStream
import kotlin.math.abs

internal class LottieSplashPlayer(
  context: Context,
  /** From [parse]; null if the animation could not be parsed. */
  private val composition: LottieComposition?,
) : SplashPlayer {
  // Frames are rendered synchronously: with async updates the first frames
  // after the launch-screen still are drawn empty.
  private val lottie = LottieAnimationView(context).apply {
    asyncUpdates = AsyncUpdates.DISABLED
  }

  override val view: View get() = lottie

  override fun load(config: SplashConfig, onReady: () -> Unit) {
    val composition = composition ?: return onReady()
    lottie.setComposition(composition)
    config.loopMarker?.let { marker ->
      if (composition.getMarker(marker) != null) lottie.setMinAndMaxFrame(marker)
    }
    lottie.repeatCount = if (config.loop) LottieDrawable.INFINITE else 0
    // Hold the first frame (it matches the launch-screen still) until play().
    lottie.frame = lottie.minFrame.toInt()
    onReady()
  }

  override fun play() {
    if (lottie.composition != null && !lottie.isAnimating) lottie.playAnimation()
  }

  override fun remainingLoopMs(): Long {
    val composition = lottie.composition ?: return 0
    if (!lottie.isAnimating) return 0
    val framesLeft = (lottie.maxFrame - lottie.frame).coerceAtLeast(0f)
    val speed = abs(lottie.speed).coerceAtLeast(0.01f)
    return (framesLeft / composition.frameRate * 1000f / speed).toLong()
  }

  override fun playOutro(name: String, onEnd: () -> Unit): Boolean {
    val composition = lottie.composition ?: return false
    composition.getMarker(name) ?: return false

    lottie.removeAllAnimatorListeners()
    lottie.repeatCount = 0
    lottie.setMinAndMaxFrame(name)
    var finished = false
    lottie.addAnimatorListener(object : AnimatorListenerAdapter() {
      override fun onAnimationEnd(animation: Animator) {
        if (finished) return
        finished = true
        lottie.removeAllAnimatorListeners()
        onEnd()
      }
    })
    lottie.playAnimation()
    return true
  }

  override fun stop() {
    lottie.removeAllAnimatorListeners()
    lottie.cancelAnimation()
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
