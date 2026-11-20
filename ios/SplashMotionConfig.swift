import UIKit

/// Build-time configuration written by `npx splash-motion generate` (or the Expo
/// plugin) into the app's asset catalog as the `SplashMotionConfig` data asset.
struct SplashMotionConfig: Decodable {
  let width: Double
  let height: Double
  let background: String
  let loop: Bool?
  let loopMarker: String?
  let introMarker: String?

  static func load() -> SplashMotionConfig? {
    guard let asset = NSDataAsset(name: "SplashMotionConfig") else { return nil }
    return try? JSONDecoder().decode(SplashMotionConfig.self, from: asset.data)
  }

  static func animationData() -> Data? {
    NSDataAsset(name: "SplashMotionAnimation")?.data
  }

  /// Named color generated in the asset catalog, falling back to the raw hex value.
  var backgroundColor: UIColor {
    if let named = UIColor(named: "SplashMotionBackground") { return named }
    return UIColor(splashMotionHex: background) ?? .white
  }
}

extension UIColor {
  convenience init?(splashMotionHex hex: String) {
    var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    if value.hasPrefix("#") { value.removeFirst() }
    guard value.count == 6 || value.count == 8, let int = UInt64(value, radix: 16) else {
      return nil
    }
    let hasAlpha = value.count == 8
    let r = CGFloat((int >> (hasAlpha ? 24 : 16)) & 0xFF) / 255
    let g = CGFloat((int >> (hasAlpha ? 16 : 8)) & 0xFF) / 255
    let b = CGFloat((int >> (hasAlpha ? 8 : 0)) & 0xFF) / 255
    let a = hasAlpha ? CGFloat(int & 0xFF) / 255 : 1
    self.init(red: r, green: g, blue: b, alpha: a)
  }
}
