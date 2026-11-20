import UIKit

/// A renderer that can play the splash animation natively.
/// Implemented by `LottieSplashPlayer`.
protocol SplashPlayer: AnyObject {
  var view: UIView { get }
  /// Plays the intro marker once (if any), then the loop.
  func play()
  /// Seconds left before the current loop or marker ends (0 if nothing is playing).
  func remainingLoopTime() -> TimeInterval
  func hasMarker(_ name: String) -> Bool
  /// Plays a marker, once (holding its last frame) or looped. `completion` gets
  /// `true` when a single play reaches its end, `false` when it is cut off.
  /// Returns `false` if this animation has no such marker.
  func playMarker(_ name: String, loop: Bool, completion: @escaping (Bool) -> Void) -> Bool
  func stop()
}
