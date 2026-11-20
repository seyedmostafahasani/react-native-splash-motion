import Lottie
import UIKit

final class LottieSplashPlayer: SplashPlayer {
  private let animationView: LottieAnimationView
  private let animation: LottieAnimation
  private let config: SplashMotionConfig

  var view: UIView { animationView }

  init?(data: Data, config: SplashMotionConfig) {
    guard let animation = try? JSONDecoder().decode(LottieAnimation.self, from: data) else {
      NSLog("[SplashMotion] Could not decode the Lottie animation.")
      return nil
    }
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

  /// The last frame of what is playing now (intro, loop or a marker).
  private var segmentEnd: AnimationFrameTime = 0

  /// Counts plays, so a late completion cannot act after a newer play started.
  private var playCount = 0

  func play() {
    if let marker = config.introMarker, range(of: marker) != nil {
      // A cut-off intro (play() or hide() took over) does not start the loop.
      // The count also covers a completion that Core Animation delivers after
      // the next marker has already started.
      _ = playMarker(marker, loop: false) { [weak self] finished in
        guard let self, finished, self.playCount == 1 else { return }
        self.playLoop()
      }
    } else {
      playLoop()
    }
  }

  private func playLoop() {
    if let marker = config.loopMarker, let loop = range(of: marker) {
      segmentEnd = loop.end
      animationView.play(fromFrame: loop.start, toFrame: loop.end, loopMode: .loop)
    } else {
      segmentEnd = animation.endFrame
      animationView.loopMode = (config.loop ?? true) ? .loop : .playOnce
      animationView.play()
    }
  }

  func remainingLoopTime() -> TimeInterval {
    guard animationView.isAnimationPlaying else { return 0 }
    let framesLeft = max(0, Double(segmentEnd - animationView.realtimeAnimationFrame))
    let speed = max(0.01, Double(abs(animationView.animationSpeed)))
    return framesLeft / animation.framerate / speed
  }

  func hasMarker(_ name: String) -> Bool {
    range(of: name) != nil
  }

  func playMarker(_ name: String, loop: Bool, completion: @escaping (Bool) -> Void) -> Bool {
    guard let marker = range(of: name) else { return false }
    playCount += 1
    segmentEnd = marker.end
    animationView.play(
      fromFrame: marker.start,
      toFrame: marker.end,
      loopMode: loop ? .loop : .playOnce
    ) { finished in
      completion(finished)
    }
    return true
  }

  func stop() {
    animationView.stop()
  }
}
