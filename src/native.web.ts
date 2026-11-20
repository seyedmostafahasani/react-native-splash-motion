import type { HideConfig, PlayConfig } from './SplashMotion.nitro';

// No native splash on web: every call is a harmless no-op.
export const native = {
  hide: (_config: HideConfig) => Promise.resolve(),
  play: (_marker: string, _config: PlayConfig) => Promise.resolve(false),
  isVisible: () => false,
};
