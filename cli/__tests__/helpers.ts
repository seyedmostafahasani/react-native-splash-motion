/**
 * Shared fixtures for the generator tests: in-memory ZIP/PNG builders and
 * temp project scaffolding. Everything is built from bytes so the tests
 * exercise the real readers instead of mocked ones.
 */
import { Buffer } from 'buffer';
import fs from 'fs';
import os from 'os';
import path from 'path';
import zlib from 'zlib';
import { vi } from 'vitest';

const tempDirs: string[] = [];

export function tempDir(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'splash-motion-test-'));
  tempDirs.push(dir);
  return dir;
}

export function removeTempDirs() {
  for (const dir of tempDirs.splice(0)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------
// ZIP (.lottie) builder — stored and deflate entries, no external dependency.

interface ZipFile {
  name: string;
  data: string | Buffer;
  deflate?: boolean;
}

export function makeZip(files: ZipFile[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;

  for (const file of files) {
    const name = Buffer.from(file.name, 'utf8');
    const raw = Buffer.isBuffer(file.data)
      ? file.data
      : Buffer.from(file.data, 'utf8');
    const method = file.deflate ? 8 : 0;
    const data = file.deflate ? zlib.deflateRawSync(raw) : raw;

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4); // version made by
    central.writeUInt16LE(20, 6); // version needed
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);

    offset += 30 + name.length + data.length;
  }

  const directory = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(directory.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, eocd]);
}

export function writeDotLottie(dir: string, files: ZipFile[]): string {
  const file = path.join(dir, 'splash.lottie');
  fs.writeFileSync(file, makeZip(files));
  return file;
}

// ---------------------------------------------------------------------------
// PNG builder — only the chunks `pngHasAlpha` inspects (IHDR, tRNS, IEND).

export function makePng({
  colorType = 6,
  trns = false,
}: { colorType?: number; trns?: boolean } = {}): Buffer {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(25); // length + type + 13 data bytes + crc
  ihdr.writeUInt32BE(13, 0);
  ihdr.write('IHDR', 4, 'ascii');
  ihdr.writeUInt32BE(1, 8); // width
  ihdr.writeUInt32BE(1, 12); // height
  ihdr[16] = 8; // bit depth
  ihdr[17] = colorType;

  const chunks = [signature, ihdr];
  if (trns) {
    const chunk = Buffer.alloc(12);
    chunk.write('tRNS', 4, 'ascii');
    chunks.push(chunk);
  }
  const iend = Buffer.alloc(12);
  iend.write('IEND', 4, 'ascii');
  chunks.push(iend);
  return Buffer.concat(chunks);
}

// ---------------------------------------------------------------------------
// Lottie fixtures.

export function shapeLayer(overrides: Record<string, unknown> = {}) {
  return { ty: 4, nm: 'shape', ind: 1, ks: {}, ...overrides };
}

/** A plain, fully opaque solid covering the whole canvas at its center. */
export function backgroundSolid(
  w: number,
  h: number,
  overrides: Record<string, unknown> = {}
) {
  return {
    ty: 1,
    nm: 'White Solid 1',
    ind: 2,
    sw: w,
    sh: h,
    sc: '#ffffff',
    ks: {
      o: { a: 0, k: 100 },
      r: { a: 0, k: 0 },
      p: { a: 0, k: [w / 2, h / 2] },
      a: { a: 0, k: [w / 2, h / 2] },
      s: { a: 0, k: [100, 100] },
    },
    ...overrides,
  };
}

export function makeLottie({
  w = 200,
  h = 100,
  layers = [] as unknown[],
  markers,
}: {
  w?: number;
  h?: number;
  layers?: unknown[];
  markers?: unknown[];
} = {}) {
  return {
    v: '5.9.0',
    fr: 30,
    ip: 0,
    op: 60,
    w,
    h,
    layers,
    ...(markers ? { markers } : {}),
  };
}

export function writeAnimation(
  dir: string,
  lottie: unknown,
  { name = 'splash.json', pretty = false } = {}
): string {
  const file = path.join(dir, name);
  fs.writeFileSync(file, JSON.stringify(lottie, null, pretty ? 2 : 0));
  return file;
}

// ---------------------------------------------------------------------------
// Native project fixtures.

export function makeIosProject(
  root: string,
  {
    appDir = 'MyApp',
    storyboardName = 'LaunchScreen',
    xcassets = true,
  }: { appDir?: string; storyboardName?: string; xcassets?: boolean } = {}
): string {
  const dir = path.join(root, 'ios', appDir);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'Info.plist'),
    `<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>UILaunchStoryboardName</key>
    <string>${storyboardName}</string>
</dict>
</plist>
`
  );
  if (xcassets) {
    fs.mkdirSync(path.join(dir, 'Images.xcassets'), { recursive: true });
  }
  return dir;
}

/** An app extension (a widget, a share extension…) next to the app's folder. */
export function makeIosExtension(
  root: string,
  name = 'MyWidget',
  { xcassets = false }: { xcassets?: boolean } = {}
): string {
  const dir = path.join(root, 'ios', name);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'Info.plist'),
    `<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>NSExtension</key>
    <dict>
        <key>NSExtensionPointIdentifier</key>
        <string>com.apple.widgetkit-extension</string>
    </dict>
</dict>
</plist>
`
  );
  if (xcassets) {
    fs.mkdirSync(path.join(dir, 'Assets.xcassets'), { recursive: true });
  }
  return dir;
}

/**
 * Makes `fs.readdirSync(<root>/ios)` list `first` first. The order of
 * `readdirSync` depends on the file system, so tests that care pin it.
 */
export function listFirstInIos(root: string, first: string) {
  const iosDir = path.join(root, 'ios');
  const readdirSync = fs.readdirSync as (...args: unknown[]) => unknown;
  vi.spyOn(fs, 'readdirSync').mockImplementation(((...args: unknown[]) => {
    const entries = readdirSync(...args);
    if (String(args[0]) !== iosDir || !Array.isArray(entries)) return entries;
    return [first, ...entries.filter((entry) => entry !== first)];
  }) as typeof fs.readdirSync);
}

export function makeAndroidProject(
  root: string,
  {
    manifest = `<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:theme="@style/AppTheme">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
            </intent-filter>
        </activity>
    </application>
</manifest>
`,
    styles = `<resources>
    <style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar"/>
</resources>
`,
  }: { manifest?: string; styles?: string } = {}
): string {
  const main = path.join(root, 'android', 'app', 'src', 'main');
  fs.mkdirSync(path.join(main, 'res', 'values'), { recursive: true });
  fs.writeFileSync(path.join(main, 'AndroidManifest.xml'), manifest);
  fs.writeFileSync(path.join(main, 'res', 'values', 'styles.xml'), styles);
  return main;
}

export function read(file: string): string {
  return fs.readFileSync(file, 'utf8');
}

export function readJson(file: string) {
  return JSON.parse(read(file));
}
