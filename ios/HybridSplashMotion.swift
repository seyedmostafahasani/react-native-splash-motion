import NitroModules

/// Nitro HybridObject backing the JS `SplashMotion` API.
class HybridSplashMotion: HybridSplashMotionModuleSpec {
  func hide(config: HideConfig) throws -> Promise<Void> {
    let promise = Promise<Void>()
    SplashMotionController.shared.hide(
      fade: config.fade ?? 220,
      minDuration: config.minDuration ?? 0,
      waitForLoop: config.waitForLoop ?? true,
      outro: config.outro
    ) {
      promise.resolve(withResult: ())
    }
    return promise
  }

  func play(marker: String, config: PlayConfig) throws -> Promise<Bool> {
    let promise = Promise<Bool>()
    SplashMotionController.shared.play(
      marker: marker,
      waitForLoop: config.waitForLoop ?? true,
      loop: config.loop ?? false
    ) { played in
      promise.resolve(withResult: played)
    }
    return promise
  }

  func isVisible() throws -> Bool {
    SplashMotionController.shared.isVisible
  }
}
