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

    guard let loaded = SplashMotionPreload.shared.take() else { return true }
    let config = loaded.config
    let player = LottieSplashPlayer(animation: loaded.animation, config: config)

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
    #if !DEBUG
    let limit = (config.timeout ?? SplashMotionConfig.defaultTimeout) / 1000
    if limit > 0 {
      DispatchQueue.main.asyncAfter(deadline: .now() + limit) { [weak self] in
        self?.timeOut(after: limit)
      }
    }
    #endif
    return true
  }

  /// Safety net: the splash removes itself if JavaScript never calls `hide()`.
  private func timeOut(after limit: TimeInterval) {
    guard container != nil, !hiding else { return }
    NSLog("[SplashMotion] hide() was not called within \(Int(limit))s. Removing the splash.")
    hiding = true
    dismiss(fade: 220)
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

      let elapsed = Date().timeIntervalSince(self.shownAt) * 1000
      let delay = max(0, minDuration - elapsed) / 1000
      DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
        self.runExit(fade: fade, waitForLoop: waitForLoop, outro: outro)
      }
    }
  }

  private func runExit(fade: Double, waitForLoop: Bool, outro: String?) {
    guard let player else {
      dismiss(fade: fade)
      return
    }
    let afterLoop = { [weak self] in
      guard let self else { return }
      if let outro,
         player.playOutro(outro, completion: { [weak self] in
           self?.dismiss(fade: fade)
         }) {
        return
      }
      self.dismiss(fade: fade)
    }
    if waitForLoop {
      let wait = min(player.remainingLoopTime(), 5)
      DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: afterLoop)
    } else {
      afterLoop()
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
    hiding = false
    callbacks.forEach { $0() }
  }
}

/// Reads and decodes the animation on a background queue at process start, so
/// the main thread doesn't pay for it when the first window appears.
final class SplashMotionPreload {
  static let shared = SplashMotionPreload()
  /// How long a preloaded animation waits for a window before it is released.
  private static let keepTime: TimeInterval = 10

  struct Loaded {
    let config: SplashMotionConfig
    let animation: LottieSplashPlayer.Animation
  }

  // Main-thread state. `loaded` is written on the background queue before it leaves `group`.
  private let group = DispatchGroup()
  private var started = false
  private var loaded: Loaded?

  func start() {
    guard !started else { return }
    started = true
    group.enter()
    // The main thread may wait on this in take(), so run at its priority.
    DispatchQueue.global(qos: .userInteractive).async {
      self.loaded = Self.load()
      self.group.leave()
    }
    // Launched without UI (push, background fetch): don't keep the animation around.
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.keepTime) {
      guard self.started, self.group.wait(timeout: .now()) == .success else { return }
      self.started = false
      self.loaded = nil
    }
  }

  /// The preloaded result, waiting for it if it is still being decoded.
  /// Loads on the spot if nothing was preloaded.
  func take() -> Loaded? {
    guard started else { return Self.load() }
    group.wait()
    started = false
    defer { loaded = nil }
    return loaded
  }

  private static func load() -> Loaded? {
    guard let config = SplashMotionConfig.load() else {
      NSLog("[SplashMotion] No config found. Run `npx splash-motion generate <file>` and rebuild.")
      return nil
    }
    guard let data = SplashMotionConfig.animationData(),
          let animation = LottieSplashPlayer.decode(data) else {
      return nil
    }
    return Loaded(config: config, animation: animation)
  }
}
