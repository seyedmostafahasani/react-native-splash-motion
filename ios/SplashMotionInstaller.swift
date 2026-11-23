import UIKit

/// Zero-config setup: invoked from `+load` (see SplashMotionLoader.m) before
/// `main()` runs, so no AppDelegate / SceneDelegate changes are needed.
/// The overlay attaches to the first app window as soon as it becomes key —
/// long before the JS bundle has finished loading.
@objc(SplashMotionInstaller)
public final class SplashMotionInstaller: NSObject {
  private static var observer: NSObjectProtocol?

  @objc public static func install() {
    guard observer == nil else { return }
    #if DEBUG
    // Debug builds with expo-dev-client open the dev launcher first. Its UI is a
    // view inside a plain root view controller, so `shouldAttach` can't tell it
    // from the app, and the touch-swallowing overlay would cover the launcher
    // until JavaScript calls `hide()` — which never happens there.
    if NSClassFromString("EXDevLauncherController") != nil { return }
    #endif
    SplashMotionPreload.shared.start()
    observer = NotificationCenter.default.addObserver(
      forName: UIWindow.didBecomeKeyNotification,
      object: nil,
      queue: .main
    ) { note in
      guard let window = note.object as? UIWindow, shouldAttach(to: window) else { return }
      SplashMotionController.shared.attach(to: window)
      if let observer {
        NotificationCenter.default.removeObserver(observer)
      }
      observer = nil
    }
  }

  /// Skip helper windows (Expo dev launcher, LogBox, dev menu…).
  private static func shouldAttach(to window: UIWindow) -> Bool {
    guard let root = window.rootViewController else { return false }
    let name = String(describing: type(of: root))
    let ignored = ["EXDevLauncher", "RCTLogBox", "RCTDevMenu", "EXDevMenu"]
    return !ignored.contains { name.hasPrefix($0) }
  }
}
