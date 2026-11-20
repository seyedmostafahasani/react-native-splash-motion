import UIKit

/// Owns the native overlay: shows it on the first app window and removes it
/// when JavaScript calls `hide()`.
final class SplashMotionController {
  static let shared = SplashMotionController()
  /// Keep in sync with `MAX_SCREEN_FRACTION` (Android) and the CLI's launch storyboard.
  static let maxScreenFraction: CGFloat = 0.9

  private let lock = NSLock()
  private var _visible = false

  // Main-thread state
  private var attached = false
  private var container: UIView?
  private var player: SplashPlayer?
  private var shownAt = Date()
  private var hiding = false
  private var pending: [() -> Void] = []

  var isVisible: Bool {
    lock.lock(); defer { lock.unlock() }
    return _visible
  }

  private func setVisible(_ value: Bool) {
    lock.lock(); _visible = value; lock.unlock()
  }

  // MARK: Showing

  /// Returns `true` once the overlay is attached (or definitively skipped).
  @discardableResult
  func attach(to window: UIWindow) -> Bool {
    guard !attached else { return true }
    attached = true

    guard let config = SplashMotionConfig.load() else {
      NSLog("[SplashMotion] No config found. Run `npx splash-motion generate <file>` and rebuild.")
      return true
    }
    guard let data = SplashMotionConfig.animationData(),
          let player = LottieSplashPlayer(data: data, config: config) else {
      return true
    }

    let container = UIView(frame: window.bounds)
    container.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    container.backgroundColor = config.backgroundColor
    container.isUserInteractionEnabled = true // swallow touches while visible
    container.layer.zPosition = .greatestFiniteMagnitude
    container.accessibilityViewIsModal = true

    let animationView = player.view
    animationView.translatesAutoresizingMaskIntoConstraints = false
    container.addSubview(animationView)
    // Configured size, scaled down (keeping the aspect ratio) to fit 90% of the screen.
    let preferredWidth = animationView.widthAnchor.constraint(equalToConstant: CGFloat(config.width))
    preferredWidth.priority = .defaultHigh
    NSLayoutConstraint.activate([
      animationView.centerXAnchor.constraint(equalTo: container.centerXAnchor),
      animationView.centerYAnchor.constraint(equalTo: container.centerYAnchor),
      animationView.widthAnchor.constraint(equalTo: animationView.heightAnchor, multiplier: CGFloat(config.width / config.height)),
      animationView.widthAnchor.constraint(lessThanOrEqualTo: container.widthAnchor, multiplier: Self.maxScreenFraction),
      animationView.heightAnchor.constraint(lessThanOrEqualTo: container.heightAnchor, multiplier: Self.maxScreenFraction),
      preferredWidth,
    ])

    window.addSubview(container)
    self.container = container
    self.player = player
    shownAt = Date()
    setVisible(true)
    player.play()
    return true
  }

  // MARK: Steps

  /// `play()` and `hide()` steps run one after another, in call order.
  private var steps: [(@escaping () -> Void) -> Void] = []
  private var stepRunning = false

  private func enqueue(_ step: @escaping (@escaping () -> Void) -> Void) {
    steps.append(step)
    runNextStep()
  }

  private func runNextStep() {
    guard !stepRunning, !steps.isEmpty else { return }
    stepRunning = true
    let step = steps.removeFirst()
    step { [weak self] in
      self?.stepRunning = false
      self?.runNextStep()
    }
  }

  /// Runs `body` once the current loop or marker ends (at most 5 s), or now.
  private func afterCurrentLoop(_ waitForLoop: Bool, _ player: SplashPlayer, _ body: @escaping () -> Void) {
    if waitForLoop {
      let wait = min(player.remainingLoopTime(), 5)
      DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: body)
    } else {
      body()
    }
  }

  // MARK: Playing a marker

  func play(
    marker: String,
    waitForLoop: Bool,
    loop: Bool,
    completion: @escaping (Bool) -> Void
  ) {
    DispatchQueue.main.async {
      guard !self.hiding, let player = self.player, player.hasMarker(marker) else {
        completion(false)
        return
      }
      self.enqueue { done in
        self.afterCurrentLoop(waitForLoop, player) {
          _ = player.playMarker(marker, loop: loop) { finished in
            if !loop { completion(finished) }
          }
          if loop { completion(true) }
          done()
        }
      }
    }
  }

  // MARK: Hiding

  func hide(
    fade: Double,
    minDuration: Double,
    waitForLoop: Bool,
    outro: String?,
    completion: @escaping () -> Void
  ) {
    DispatchQueue.main.async {
      guard self.container != nil else {
        completion()
        return
      }
      self.pending.append(completion)
      if self.hiding { return }
      self.hiding = true

      // The last step: it ends with the overlay, so it never calls `done`.
      self.enqueue { _ in
        let elapsed = Date().timeIntervalSince(self.shownAt) * 1000
        let delay = max(0, minDuration - elapsed) / 1000
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
          self.runExit(fade: fade, waitForLoop: waitForLoop, outro: outro)
        }
      }
    }
  }

  private func runExit(fade: Double, waitForLoop: Bool, outro: String?) {
    guard let player else {
      dismiss(fade: fade)
      return
    }
    afterCurrentLoop(waitForLoop, player) { [weak self] in
      guard let self else { return }
      if let outro,
         player.playMarker(outro, loop: false, completion: { [weak self] _ in
           self?.dismiss(fade: fade)
         }) {
        return
      }
      self.dismiss(fade: fade)
    }
  }

  private func dismiss(fade: Double) {
    guard let container else {
      flush()
      return
    }
    let finish = { [weak self] in
      guard let self else { return }
      container.removeFromSuperview()
      self.player?.stop()
      self.player = nil
      self.container = nil
      self.setVisible(false)
      self.flush()
    }
    if fade > 0 {
      UIView.animate(withDuration: fade / 1000, delay: 0, options: [.curveEaseOut]) {
        container.alpha = 0
      } completion: { _ in
        finish()
      }
    } else {
      finish()
    }
  }

  private func flush() {
    let callbacks = pending
    pending.removeAll()
    steps.removeAll()
    stepRunning = false
    hiding = false
    callbacks.forEach { $0() }
  }
}
