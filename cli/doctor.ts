/**
 * `npx splash-motion doctor` — validates the generated setup without building:
 * assets, launch-screen wiring, marker names and common conflicts.
 */
import fs from 'fs';
import path from 'path';
import {
  androidMain,
  expoSplashScreenUsage,
  findIosApp,
  findMainActivity,
  GENERATED_BY,
  parseHex,
} from './core';

export interface DoctorReport {
  errors: number;
  warnings: number;
}

interface Reporter {
  ok(message: string): void;
  info(message: string): void;
  warn(message: string): void;
  fail(message: string): void;
}

interface RuntimeConfig {
  background?: string;
  loopMarker?: string | null;
  introMarker?: string | null;
}

interface Animation {
  markers?: { cm?: string }[];
}

interface AppPackage {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
}

function readJsonFile(file: string): unknown {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function readTextFile(file: string): string {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return '';
  }
}

/** Uses the generator's own conversion so doctor cannot drift from it. */
function srgbComponents(hex: string): number[] | null {
  try {
    const { r, g, b } = parseHex(hex);
    return [r, g, b];
  } catch {
    return null;
  }
}

/**
 * Checks the generated config + animation shared by both platforms and
 * validates marker names against the animation. Returns the config, or null
 * when the setup is missing entirely (deeper checks are then pointless).
 */
function checkGeneratedAssets(
  platform: string,
  configFile: string,
  animationFile: string,
  r: Reporter
): RuntimeConfig | null {
  const config = readJsonFile(configFile) as RuntimeConfig | null;
  if (!config) {
    r.fail(
      `${platform}: no generated config found. Run \`npx splash-motion generate <animation>\` and rebuild.`
    );
    return null;
  }
  r.ok(`${platform}: generated config found`);

  const animation = readJsonFile(animationFile) as Animation | null;
  if (!animation) {
    r.fail(
      `${platform}: the animation is missing or invalid. Re-run \`npx splash-motion generate\`.`
    );
    return config;
  }
  r.ok(`${platform}: animation file is valid JSON`);

  const markers = (animation.markers ?? [])
    .map((m) => m?.cm)
    .filter((m): m is string => Boolean(m));
  for (const [kind, marker] of [
    ['loop', config.loopMarker],
    ['intro', config.introMarker],
  ] as const) {
    if (!marker) continue;
    if (markers.includes(marker)) {
      r.ok(`${platform}: ${kind} marker "${marker}" exists`);
    } else {
      r.fail(
        `${platform}: ${kind} marker "${marker}" is not in the animation` +
          (markers.length
            ? ` (available: ${markers.join(', ')}).`
            : ' (the animation has no markers).') +
          ` Fix --${kind}-marker or add the marker in After Effects.`
      );
    }
  }
  if (markers.length > 0) {
    r.info(
      `${platform}: markers: ${markers.join(', ')}` +
        (markers.includes('outro')
          ? ''
          : ' — add an "outro" marker to use hide({ outro: \'outro\' })')
    );
  }
  return config;
}

/**
 * Returns a description of the first structural problem in an XML document
 * (unterminated construct, mismatched or unclosed tag, no root element), or
 * null when it is well-formed. A small tag-balance check: enough to catch a
 * truncated or hand-broken storyboard without an XML dependency.
 */
export function xmlProblem(xml: string): string | null {
  const stack: string[] = [];
  let sawRoot = false;
  let i = 0;
  while (i < xml.length) {
    const open = xml.indexOf('<', i);
    if (open === -1) break;
    // Comments, CDATA and processing instructions are skipped whole.
    const delimited = [
      ['<!--', '-->', 'a comment'],
      ['<![CDATA[', ']]>', 'a CDATA section'],
      ['<?', '?>', 'a processing instruction'],
    ].find(([start]) => xml.startsWith(start!, open));
    if (delimited) {
      const [start, end, what] = delimited as [string, string, string];
      const close = xml.indexOf(end, open + start.length);
      if (close === -1) return `${what} is never closed`;
      i = close + end.length;
      continue;
    }
    if (xml.startsWith('<!', open)) {
      const close = xml.indexOf('>', open);
      if (close === -1) return 'an unterminated declaration';
      i = close + 1;
      continue;
    }

    // A tag: find its closing ">" outside quoted attribute values.
    let quote = '';
    let end = -1;
    for (let j = open + 1; j < xml.length; j++) {
      const ch = xml[j]!;
      if (quote) {
        if (ch === quote) quote = '';
      } else if (ch === '"' || ch === "'") {
        quote = ch;
      } else if (ch === '>') {
        end = j;
        break;
      }
    }
    if (end === -1) return 'an unterminated tag';
    const body = xml.slice(open + 1, end);
    i = end + 1;

    if (body.startsWith('/')) {
      const name = body.slice(1).trim();
      const expected = stack.pop();
      if (expected !== name)
        return expected
          ? `</${name}> where </${expected}> was expected`
          : `an unexpected </${name}>`;
    } else {
      const name = body.match(/^[^\s/>]+/)?.[0];
      if (!name) return 'a tag with no name';
      if (stack.length === 0) {
        if (sawRoot) return 'more than one root element';
        sawRoot = true;
      }
      if (!body.endsWith('/')) stack.push(name);
    }
  }
  if (stack.length > 0) return `<${stack[stack.length - 1]}> is never closed`;
  return sawRoot ? null : 'no root element';
}

function checkIos(projectRoot: string, r: Reporter) {
  const app = findIosApp(projectRoot);
  if (!app) {
    r.fail('iOS: no app folder with an Info.plist found in ios/.');
    return;
  }
  if (!app.xcassets) {
    r.fail(`iOS: no .xcassets catalog found in ${app.dir}.`);
    return;
  }

  const config = checkGeneratedAssets(
    'iOS',
    path.join(app.xcassets, 'SplashMotionConfig.dataset', 'config.json'),
    path.join(app.xcassets, 'SplashMotionAnimation.dataset', 'animation.json'),
    r
  );
  if (!config) return;

  const storyboard = path.join(app.dir, `${app.storyboardName}.storyboard`);
  const xml = readTextFile(storyboard);
  if (!xml) {
    r.fail(`iOS: ${app.storyboardName}.storyboard not found.`);
    return;
  }
  const problem = xmlProblem(xml);
  if (problem) {
    r.fail(
      `iOS: ${app.storyboardName}.storyboard is not valid XML (${problem}). ` +
        'Xcode will fail to build it. Re-run `npx splash-motion generate`.'
    );
    return;
  }
  if (!xml.includes(GENERATED_BY)) {
    r.warn(
      `iOS: ${app.storyboardName}.storyboard was not generated by SplashMotion, so the ` +
        'animated splash may not match your config. Re-run `npx splash-motion generate` ' +
        '(your current file is kept as a .bak copy).'
    );
    return;
  }
  if (xml.includes('launchScreen="YES"')) {
    r.ok('iOS: launch storyboard found');
  } else {
    r.warn(`iOS: ${app.storyboardName}.storyboard is not a launch screen.`);
  }

  const components = config.background
    ? srgbComponents(config.background)
    : null;
  const color = xml.match(
    /<color key="backgroundColor" red="([^"]+)" green="([^"]+)" blue="([^"]+)"/
  );
  const actual = color?.slice(1).map(Number);
  if (components && actual && actual.every(Number.isFinite)) {
    // Storyboard components are rounded to three decimals by the generator.
    const matches = components.every(
      (c, i) => Math.abs(c - (actual[i] ?? Infinity)) <= 0.002
    );
    if (matches) {
      r.ok('iOS: storyboard background matches the config');
    } else {
      r.warn(
        `iOS: the storyboard background differs from the config (${config.background}). ` +
          'Re-run `npx splash-motion generate` and delete the app from the device (iOS caches launch screens).'
      );
    }
  }
}

function checkAndroid(projectRoot: string, r: Reporter) {
  const main = androidMain(projectRoot);
  const config = checkGeneratedAssets(
    'Android',
    path.join(main, 'assets', 'splashmotion', 'config.json'),
    path.join(main, 'assets', 'splashmotion', 'animation.json'),
    r
  );
  if (!config) return;

  const colors = readTextFile(
    path.join(main, 'res', 'values', 'splashmotion_colors.xml')
  );
  const hex = colors.match(
    /<color name="splashmotion_background">([^<]+)<\/color>/
  )?.[1];
  if (!hex) {
    r.fail('Android: splashmotion_background color missing. Re-run generate.');
  } else if (
    config.background &&
    hex.toUpperCase() !== config.background.toUpperCase()
  ) {
    r.warn(
      `Android: colors.xml (${hex}) differs from the config background (${config.background}). Re-run generate.`
    );
  } else {
    r.ok('Android: background color matches the config');
  }

  const manifest = readTextFile(path.join(main, 'AndroidManifest.xml'));
  const activity = findMainActivity(manifest);
  if (!activity) {
    r.fail('Android: no MainActivity found in AndroidManifest.xml.');
  } else if (!activity.includes('android:theme="@style/SplashMotionTheme"')) {
    r.fail(
      'Android: MainActivity does not use @style/SplashMotionTheme. Re-run generate.'
    );
  } else {
    r.ok('Android: MainActivity uses the splash theme');
  }

  const styles = readTextFile(path.join(main, 'res', 'values', 'styles.xml'));
  if (
    styles.includes('<style name="SplashMotionTheme"') &&
    styles.includes('postSplashScreenTheme')
  ) {
    r.ok('Android: SplashMotionTheme has a postSplashScreenTheme');
  } else {
    r.fail(
      'Android: SplashMotionTheme (with postSplashScreenTheme) is missing from styles.xml. Re-run generate.'
    );
  }
}

export function runDoctor(projectRoot: string): DoctorReport {
  console.log('\n🩺 SplashMotion doctor\n');
  const report: DoctorReport = { errors: 0, warnings: 0 };
  const r: Reporter = {
    ok: (message) => console.log(`  ✓ ${message}`),
    info: (message) => console.log(`  ℹ ${message}`),
    warn: (message) => {
      report.warnings++;
      console.log(`  ⚠ ${message}`);
    },
    fail: (message) => {
      report.errors++;
      console.log(`  ✗ ${message}`);
    },
  };

  const pkg = readJsonFile(
    path.join(projectRoot, 'package.json')
  ) as AppPackage | null;
  if (!pkg) {
    r.fail(
      `No package.json found at ${projectRoot}. Run from your app's root (or pass --project-root).`
    );
  } else {
    if (expoSplashScreenUsage(projectRoot).includes('dependency')) {
      r.warn(
        'expo-splash-screen is installed. SplashMotion replaces it — remove it so the two splash screens don’t fight.'
      );
    }
    const deps = { ...pkg.dependencies, ...pkg.devDependencies };
    if (!deps['react-native-nitro-modules']) {
      r.warn(
        'react-native-nitro-modules is not a dependency. Install it: yarn add react-native-nitro-modules'
      );
    }
  }

  const hasIos = fs.existsSync(path.join(projectRoot, 'ios'));
  const hasAndroid = fs.existsSync(androidMain(projectRoot));
  if (!hasIos && !hasAndroid) {
    r.fail(
      'No ios/ or android/ project found. For Expo, run `npx expo prebuild` first.'
    );
  }
  if (hasIos) checkIos(projectRoot, r);
  if (hasAndroid) checkAndroid(projectRoot, r);

  r.info(
    "Marker checks cover loopMarker and introMarker only; play('name') and hide({ outro: 'name' }) are runtime options and are not validated here."
  );
  console.log(`\n  ${report.errors} error(s), ${report.warnings} warning(s)\n`);
  if (report.errors === 0 && report.warnings === 0) {
    console.log(
      '  Everything looks good. Rebuild the app after changing the animation.\n'
    );
  }
  return report;
}
