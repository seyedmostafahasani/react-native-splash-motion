package com.margelo.nitro.splashmotion

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise

/** Nitro HybridObject backing the JS `SplashMotion` API. */
@DoNotStrip
class HybridSplashMotion : HybridSplashMotionModuleSpec() {
  override fun hide(config: HideConfig): Promise<Unit> {
    val promise = Promise<Unit>()
    SplashMotionController.hide(
      fade = (config.fade ?: 220.0).toLong(),
      minDuration = (config.minDuration ?: 0.0).toLong(),
      waitForLoop = config.waitForLoop ?: true,
      outro = config.outro,
    ) { promise.resolve(Unit) }
    return promise
  }

  override fun play(marker: String, config: PlayConfig): Promise<Boolean> {
    val promise = Promise<Boolean>()
    SplashMotionController.play(
      marker = marker,
      waitForLoop = config.waitForLoop ?: true,
      loop = config.loop ?: false,
    ) { played -> promise.resolve(played) }
    return promise
  }

  override fun isVisible(): Boolean = SplashMotionController.isVisible()
}
