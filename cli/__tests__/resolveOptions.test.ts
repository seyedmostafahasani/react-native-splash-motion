import fs from 'fs';
import path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveOptions } from '../core';
import type { ResolvedOptions } from '../core';
import {
  backgroundSolid,
  makeLottie,
  makePng,
  removeTempDirs,
  shapeLayer,
  tempDir,
  writeAnimation,
} from './helpers';

let root: string;

beforeEach(() => {
  root = tempDir();
  vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
  removeTempDirs();
});

function generate(lottie: unknown, options: Record<string, unknown> = {}) {
  const source = writeAnimation(root, lottie);
  return resolveOptions(root, { source, ...options });
}

function outputJson(o: ResolvedOptions) {
  return JSON.parse(o.data.toString('utf8'));
}

describe('resolveOptions — input validation', () => {
  it('requires a source', () => {
    expect(() => resolveOptions(root, {})).toThrow(/Missing animation file/);
  });

  it('rejects a missing file', () => {
    expect(() => resolveOptions(root, { source: 'nope.json' })).toThrow(
      /Animation file not found/
    );
  });

  it('rejects unsupported extensions', () => {
    const source = writeAnimation(root, makeLottie(), { name: 'splash.txt' });
    expect(() => resolveOptions(root, { source })).toThrow(
      /Unsupported file type "\.txt"/
    );
  });

  it('rejects invalid JSON', () => {
    const source = path.join(root, 'splash.json');
    fs.writeFileSync(source, '{not json');
    expect(() => resolveOptions(root, { source })).toThrow(
      /not a valid Lottie JSON file/
    );
  });

  it('defaults to 160 wide with the animation aspect ratio', () => {
    const o = generate(makeLottie({ w: 200, h: 100 }));
    expect(o.width).toBe(160);
    expect(o.height).toBe(80);
  });

  it('scales the height from an explicit width', () => {
    const o = generate(makeLottie({ w: 200, h: 100 }), { width: 300 });
    expect(o.width).toBe(300);
    expect(o.height).toBe(150);
  });

  it('accepts an explicit height and numeric strings', () => {
    const o = generate(makeLottie(), { width: '120', height: '50' });
    expect(o.width).toBe(120);
    expect(o.height).toBe(50);
  });

  it.each([0, -10, 'abc', ''])('rejects invalid width %j', (width) => {
    expect(() => generate(makeLottie(), { width })).toThrow(
      /width\/height must be positive numbers/
    );
  });

  it('falls back to a square when the animation has no dimensions', () => {
    const o = generate({ layers: [shapeLayer()] });
    expect(o.width).toBe(160);
    expect(o.height).toBe(160);
  });

  it('defaults to looping without a loop marker', () => {
    const o = generate(makeLottie());
    expect(o.loop).toBe(true);
    expect(o.loopMarker).toBeNull();
  });

  it('passes through loop options', () => {
    const o = generate(makeLottie(), {
      loop: false,
      loopMarker: 'loop',
      introMarker: 'intro',
    });
    expect(o.loop).toBe(false);
    expect(o.loopMarker).toBe('loop');
    expect(o.introMarker).toBe('intro');
  });

  it('has no intro marker by default', () => {
    expect(generate(makeLottie()).introMarker).toBeNull();
  });
});

describe('resolveOptions — background color', () => {
  it('defaults to white', () => {
    expect(generate(makeLottie()).background.hex).toBe('#FFFFFF');
  });

  it('normalizes case and computes components', () => {
    const { background } = generate(makeLottie(), { background: '#0f6e56' });
    expect(background.hex).toBe('#0F6E56');
    expect(background.r).toBeCloseTo(0x0f / 255);
    expect(background.g).toBeCloseTo(0x6e / 255);
    expect(background.b).toBeCloseTo(0x56 / 255);
  });

  it('expands 3-digit hex', () => {
    expect(generate(makeLottie(), { background: 'fff' }).background.hex).toBe(
      '#FFFFFF'
    );
  });

  it.each(['red', '#12345', '#GGGGGG'])('rejects invalid color %j', (hex) => {
    expect(() => generate(makeLottie(), { background: hex })).toThrow(
      /Invalid color/
    );
  });
});

describe('resolveOptions — still image', () => {
  it('rejects a missing still', () => {
    expect(() => generate(makeLottie(), { still: 'nope.png' })).toThrow(
      /Still image not found/
    );
  });

  it('rejects non-PNG stills', () => {
    const still = path.join(root, 'still.jpg');
    fs.writeFileSync(still, makePng());
    expect(() => generate(makeLottie(), { still })).toThrow(/must be a PNG/);
  });

  it('warns when the still has no transparency', () => {
    const still = path.join(root, 'still.png');
    fs.writeFileSync(still, makePng({ colorType: 2 }));
    generate(makeLottie(), { still });
    expect(console.warn).toHaveBeenCalledWith(
      expect.stringContaining('has no transparency')
    );
  });

  it.each([
    ['alpha color type', makePng({ colorType: 6 })],
    ['grayscale+alpha color type', makePng({ colorType: 4 })],
    ['tRNS chunk', makePng({ colorType: 2, trns: true })],
  ])('accepts a transparent still (%s)', (_name, png) => {
    const still = path.join(root, 'still.png');
    fs.writeFileSync(still, png);
    generate(makeLottie(), { still });
    expect(console.warn).not.toHaveBeenCalled();
  });
});

describe('resolveOptions — background solid removal', () => {
  it('copies an untouched animation byte-for-byte', () => {
    const lottie = makeLottie({ layers: [shapeLayer()] });
    const source = writeAnimation(root, lottie, { pretty: true });
    const o = resolveOptions(root, { source });
    expect(o.data.toString('utf8')).toBe(fs.readFileSync(source, 'utf8'));
  });

  it('removes a full-canvas solid at the bottom of the stack', () => {
    const o = generate(
      makeLottie({ layers: [shapeLayer(), backgroundSolid(200, 100)] })
    );
    const out = outputJson(o);
    expect(out.layers).toHaveLength(1);
    expect(out.layers[0].nm).toBe('shape');
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('White Solid 1')
    );
  });

  it('removes stacked bottom solids but keeps the artwork', () => {
    const o = generate(
      makeLottie({
        layers: [
          shapeLayer(),
          backgroundSolid(200, 100, { ind: 2, nm: 'BG 1' }),
          backgroundSolid(200, 100, { ind: 3, nm: 'BG 2' }),
        ],
      })
    );
    expect(outputJson(o).layers).toHaveLength(1);
  });

  it('keeps the solid with --keep-background', () => {
    const source = writeAnimation(
      root,
      makeLottie({ layers: [shapeLayer(), backgroundSolid(200, 100)] })
    );
    const o = resolveOptions(root, { source, keepBackground: true });
    expect(o.data.toString('utf8')).toBe(fs.readFileSync(source, 'utf8'));
  });

  it('keeps a solid that is not at the bottom of the stack', () => {
    const o = generate(
      makeLottie({ layers: [backgroundSolid(200, 100), shapeLayer()] })
    );
    expect(outputJson(o).layers).toHaveLength(2);
  });

  it('keeps the only layer even when it is a background solid', () => {
    const o = generate(makeLottie({ layers: [backgroundSolid(200, 100)] }));
    expect(outputJson(o).layers).toHaveLength(1);
  });

  it('removes a solid with split (separate x/y) positions', () => {
    const solid = backgroundSolid(200, 100, {
      ks: {
        o: { a: 0, k: 100 },
        r: { a: 0, k: 0 },
        p: { s: true, x: { a: 0, k: 100 }, y: { a: 0, k: 50 } },
        a: { a: 0, k: [100, 50] },
        s: { a: 0, k: [100, 100] },
      },
    });
    const o = generate(makeLottie({ layers: [shapeLayer(), solid] }));
    expect(outputJson(o).layers).toHaveLength(1);
  });

  it('removes a solid with a default (missing) transform', () => {
    const o = generate(
      makeLottie({
        layers: [shapeLayer(), backgroundSolid(200, 100, { ks: undefined })],
      })
    );
    expect(outputJson(o).layers).toHaveLength(1);
  });

  const keptCases: [string, Record<string, unknown>][] = [
    ['animated opacity', { ks: { o: { a: 1, k: 100 } } }],
    ['parented to another layer', { parent: 5 }],
    ['hidden', { hd: true }],
    ['masked', { hasMask: true }],
    ['mask properties', { masksProperties: [{}] }],
    ['used as a matte', { tt: 1 }],
    ['matte target', { td: 1 }],
    ['blend mode', { bm: 1 }],
    ['smaller than the canvas', { sw: 50 }],
    ['rotated', { ks: { r: { a: 0, k: 45 } } }],
    ['transparent', { ks: { o: { a: 0, k: 80 } } }],
  ];

  it.each(keptCases)('keeps a solid with %s', (_name, overrides) => {
    const solid = backgroundSolid(200, 100, overrides);
    // Merge partial transform overrides with the covering defaults.
    if (overrides.ks) {
      solid.ks = { ...backgroundSolid(200, 100).ks, ...overrides.ks } as never;
    }
    const o = generate(makeLottie({ layers: [shapeLayer(), solid] }));
    expect(outputJson(o).layers).toHaveLength(2);
  });

  it('keeps a solid another layer is parented to', () => {
    const o = generate(
      makeLottie({
        layers: [shapeLayer({ parent: 2 }), backgroundSolid(200, 100)],
      })
    );
    expect(outputJson(o).layers).toHaveLength(2);
  });
});
