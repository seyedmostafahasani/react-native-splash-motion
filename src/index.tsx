import { useEffect, useRef } from 'react';
import { native } from './native';
import type { HideConfig, PlayConfig } from './SplashMotion.nitro';

export type HideOptions = HideConfig;
export type PlayOptions = PlayConfig;

/**
 * Hide the splash screen. Resolves once it is fully gone.
 *
 * By default the current animation loop finishes first, then the splash
 * fades out — so it never gets cut off mid-motion.
 *
 * @example
 * await SplashMotion.hide();
 * await SplashMotion.hide({ outro: 'outro', minDuration: 1200 });
 */
function hide(options: HideOptions = {}): Promise<void> {
  return native.hide(options);
}

/**
 * Play a marker while the splash stays on screen. By default the current loop
 * finishes first, then the marker plays once and holds its last frame.
 * Calls to `play()` and `hide()` run in order, so a `hide()` after it waits for
 * the marker to finish (unless `waitForLoop: false`).
 *
 * Resolves `true` when the marker has played once (for `loop: true`, when it
 * starts), or `false` if the splash is gone or hiding, or the marker does not exist.
 *
 * @example
 * SplashMotion.play('success');               // start the exit early
 * await SplashMotion.hide({ fade: 200 });     // reveal once the app has drawn
 */
function play(marker: string, options: PlayOptions = {}): Promise<boolean> {
  return native.play(marker, options);
}

/** `true` while the splash is on screen. */
function isVisible(): boolean {
  return native.isVisible();
}

/**
 * Hide the splash as soon as `ready` becomes `true`.
 *
 * @example
 * const fontsLoaded = useFonts(...);
 * useHideSplash(fontsLoaded && !!user);
 */
export function useHideSplash(
  ready: boolean = true,
  options?: HideOptions
): void {
  const optionsRef = useRef(options);
  optionsRef.current = options;

  useEffect(() => {
    if (ready) {
      hide(optionsRef.current);
    }
  }, [ready]);
}

/**
 * Play `marker` once, as soon as `ready` becomes `true`.
 *
 * @example
 * useSplashStep(signedIn, 'success');
 * useHideSplash(homeDrawn);
 */
export function useSplashStep(
  ready: boolean,
  marker: string,
  options?: PlayOptions
): void {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const played = useRef(false);

  useEffect(() => {
    if (ready && !played.current) {
      played.current = true;
      play(marker, optionsRef.current);
    }
  }, [ready, marker]);
}

const SplashMotion = { hide, play, isVisible };

export { hide, play, isVisible };
export default SplashMotion;
