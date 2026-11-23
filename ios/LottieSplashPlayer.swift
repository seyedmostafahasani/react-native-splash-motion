import Lottie
import UIKit

final class LottieSplashPlayer: SplashPlayer {
  private let animationView: LottieAnimationView
  private let animation: LottieAnimation
  private let config: SplashMotionConfig

  private var engineChecked = false

  var view: UIView { animationView }

  typealias Animation = LottieAnimation

  /// Decodes the animation. Safe to call off the main thread.
  static func decode(_ data: Data) -> Animation? {
    guard let animation = try? JSONDecoder().decode(LottieAnimation.self, from: data) else {
      NSLog("[SplashMotion] Could not decode the Lottie animation.")
      return nil
    }
    return animation
  }

  init(animation: Animation, config: SplashMotionConfig) {
    self.animation = animation
    self.config = config
    // The Core Animation engine renders on the system render server,
    // so the animation stays smooth even while React Native boots.
    animationView = LottieAnimationView(
      animation: animation,
      configuration: LottieConfiguration(renderingEngine: .automatic)
    )
    animationView.contentMode = .scaleAspectFit
    animationView.backgroundBehavior = .pauseAndRestore
    animationView.backgroundColor = .clear
  }

  private func range(of marker: String) -> (start: AnimationFrameTime, end: AnimationFrameTime)? {
    guard let start = animation.frameTime(forMarker: marker) else { return nil }
    let duration = animation.durationFrameTime(forMarker: marker) ?? 0
    return (start, start + duration)
  }

  func play() {
    if let marker = config.loopMarker, let loop = range(of: marker) {
      animationView.play(fromFrame: loop.start, toFrame: loop.end, loopMode: .loop)
    } else {
      animationView.loopMode = (config.loop ?? true) ? .loop : .playOnce
      animationView.play()
    }
    checkEngine()
  }

  /// With `.automatic`, Lottie falls back to its main-thread engine for
  /// animations Core Animation can't render. It decides on the first display.
  private func checkEngine() {
    guard !engineChecked else { return }
    engineChecked = true
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
      guard self?.animationView.currentRenderingEngine == .mainThread else { return }
      NSLog(
        "[SplashMotion] This animation uses features the Core Animation engine can't render, " +
        "so it plays on the main thread and may stutter while the app starts."
      )
    }
  }

  func remainingLoopTime() -> TimeInterval {
    guard animationView.isAnimationPlaying else { return 0 }
    let end = config.loopMarker.flatMap { range(of: $0)?.end } ?? animation.endFrame
    let framesLeft = max(0, Double(end - animationView.realtimeAnimationFrame))
    let speed = max(0.01, Double(abs(animationView.animationSpeed)))
    return framesLeft / animation.framerate / speed
  }

  func playOutro(_ name: String, completion: @escaping () -> Void) -> Bool {
    guard let outro = range(of: name) else { return false }
    animationView.play(fromFrame: outro.start, toFrame: outro.end, loopMode: .playOnce) { _ in
      completion()
    }
    return true
  }

  func stop() {
    animationView.stop()
  }
}
