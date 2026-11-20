package com.margelo.nitro.splashmotion

import android.view.View

/**
 * A renderer that plays the splash animation natively. Implemented by `LottieSplashPlayer`.
 * `view` is created on the main thread; every method runs on the render thread.
 */
internal interface SplashPlayer {
  /** The full-screen view the splash draws into (background included). */
  val view: View
  /** Prepares the animation; `onReady` runs once its first frame is on screen. */
  fun load(bytes: ByteArray, config: SplashConfig, background: Int, density: Float, onReady: () -> Unit)
  /** Starts playing (the intro marker once, if any, then the loop); called once the system splash is gone. */
  fun play()
  /** Milliseconds left before the current loop or marker ends (0 if nothing is playing). */
  fun remainingLoopMs(): Long
  fun hasMarker(name: String): Boolean
  /**
   * Plays a marker, once (holding its last frame) or looped. `onEnd` gets `true` when a
   * single play reaches its end, `false` when it is cut off. Returns `false` if this
   * animation has no such marker.
   */
  fun playMarker(name: String, loop: Boolean, onEnd: (Boolean) -> Unit): Boolean
  /** Fades the whole splash (background and animation) out. */
  fun fade(durationMs: Long, onEnd: () -> Unit)
  fun stop()
}
