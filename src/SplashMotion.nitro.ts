import type { HybridObject } from 'react-native-nitro-modules';

/**
 * Options for `SplashMotion.hide()`. Every field is optional — the defaults
 * give a polished result with zero configuration.
 */
export interface HideConfig {
  /** Fade-out duration in ms once the animation is done. `0` disables it. Default: `220`. */
  fade?: number;
  /** Keep the splash visible at least this long (ms since launch). Default: `0`. */
  minDuration?: number;
  /** Let the current loop (or `play()` marker) finish instead of cutting it mid-way. Default: `true`. */
  waitForLoop?: boolean;
  /** Play an outro before leaving: the name of a marker in your animation (e.g. `"outro"`). */
  outro?: string;
}

/** Options for `SplashMotion.play()`. Every field is optional. */
export interface PlayConfig {
  /** Let the current loop (or previous marker) finish before the marker starts. Default: `true`. */
  waitForLoop?: boolean;
  /** Loop the marker instead of playing it once and holding its last frame. Default: `false`. */
  loop?: boolean;
}

export interface SplashMotionModule extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  hide(config: HideConfig): Promise<void>;
  play(marker: string, config: PlayConfig): Promise<boolean>;
  isVisible(): boolean;
}
