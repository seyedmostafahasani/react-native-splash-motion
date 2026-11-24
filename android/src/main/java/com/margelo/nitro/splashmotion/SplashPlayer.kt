package com.margelo.nitro.splashmotion

import android.view.View

/** A renderer that plays the splash animation natively. Implemented by `LottieSplashPlayer`. */
internal interface SplashPlayer {
  val view: View
  /** Prepares the animation, showing its first frame. */
  fun load(config: SplashConfig, density: Float, onReady: () -> Unit)
  /** Starts playing; called once the system splash is gone. */
  fun play()
  /** Milliseconds left before the current loop ends (0 if not looping). */
  fun remainingLoopMs(): Long
  /** Plays the outro marker. Returns `false` if this animation has no such marker. */
  fun playOutro(name: String, onEnd: () -> Unit): Boolean
  /** Fades the animation out, then calls [onEnd]. */
  fun fade(durationMs: Long, onEnd: () -> Unit)
  fun stop()
}
