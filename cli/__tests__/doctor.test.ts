import fs from 'fs';
import path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  generateAndroidFiles,
  generateIos,
  patchAndroidProject,
  resolveOptions,
} from '../core';
import { runDoctor, xmlProblem } from '../doctor';
import {
  listFirstInIos,
  makeAndroidProject,
  makeIosExtension,
  makeIosProject,
  makeLottie,
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

function writePackageJson(deps: Record<string, string> = {}) {
  fs.writeFileSync(
    path.join(root, 'package.json'),
    JSON.stringify({
      dependencies: { 'react-native-nitro-modules': '^0.37.1', ...deps },
    })
  );
}

/** A complete, healthy project: both platforms generated and patched. */
function setupGeneratedProject({
  markers,
  loopMarker,
  patch = true,
}: {
  markers?: unknown[];
  loopMarker?: string;
  patch?: boolean;
} = {}) {
  makeIosProject(root);
  makeAndroidProject(root);
  writePackageJson();
  const source = writeAnimation(
    root,
    makeLottie({ layers: [shapeLayer()], markers })
  );
  const o = resolveOptions(root, { source, loopMarker });
  generateIos(root, o);
  generateAndroidFiles(root, o);
  if (patch) patchAndroidProject(root);
}

describe('doctor', () => {
  it('passes a freshly generated project with no errors or warnings', () => {
    setupGeneratedProject();
    const report = runDoctor(root);
    expect(report).toEqual({ errors: 0, warnings: 0 });
  });

  it('checks the app, not an app extension listed before it', () => {
    makeIosExtension(root, 'MyWidget');
    listFirstInIos(root, 'MyWidget');
    setupGeneratedProject();
    expect(runDoctor(root)).toEqual({ errors: 0, warnings: 0 });
  });

  it('fails on an empty directory', () => {
    const report = runDoctor(root);
    expect(report.errors).toBeGreaterThanOrEqual(2); // package.json + projects
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('No package.json found')
    );
  });

  it('fails when the projects were never generated', () => {
    makeIosProject(root);
    makeAndroidProject(root);
    writePackageJson();
    const report = runDoctor(root);
    expect(report.errors).toBeGreaterThanOrEqual(2);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('no generated config found')
    );
  });

  it('accepts a loop marker that exists in the animation', () => {
    setupGeneratedProject({
      markers: [{ cm: 'loop', tm: 0, dr: 30 }],
      loopMarker: 'loop',
    });
    expect(runDoctor(root).errors).toBe(0);
  });

  it('catches a loop marker missing from the animation', () => {
    setupGeneratedProject({ loopMarker: 'nope' });
    const report = runDoctor(root);
    expect(report.errors).toBe(2); // iOS + Android
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('loop marker "nope" is not in the animation')
    );
  });

  it('lists available marker names to help configure hide({ outro })', () => {
    setupGeneratedProject({ markers: [{ cm: 'loop', tm: 0, dr: 30 }] });
    runDoctor(root);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('add an "outro" marker')
    );
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('Marker checks cover loopMarker only')
    );
  });

  it('warns when expo-splash-screen is installed', () => {
    setupGeneratedProject();
    writePackageJson({ 'expo-splash-screen': '~0.30.0' });
    const report = runDoctor(root);
    expect(report.warnings).toBe(1);
    expect(report.errors).toBe(0);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('expo-splash-screen is installed')
    );
  });

  it('warns when react-native-nitro-modules is missing', () => {
    setupGeneratedProject();
    fs.writeFileSync(path.join(root, 'package.json'), '{}');
    const report = runDoctor(root);
    expect(report.warnings).toBe(1);
    expect(report.errors).toBe(0);
  });

  it('warns when the storyboard background is stale', () => {
    setupGeneratedProject();
    const configFile = path.join(
      root,
      'ios',
      'MyApp',
      'Images.xcassets',
      'SplashMotionConfig.dataset',
      'config.json'
    );
    const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
    config.background = '#000000';
    fs.writeFileSync(configFile, JSON.stringify(config));

    const report = runDoctor(root);
    expect(report.errors).toBe(0);
    expect(report.warnings).toBeGreaterThanOrEqual(1);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('storyboard background differs from the config')
    );
  });

  it('accepts equivalent storyboard color formatting and rounding', () => {
    setupGeneratedProject();
    const storyboard = path.join(
      root,
      'ios',
      'MyApp',
      'LaunchScreen.storyboard'
    );
    const xml = fs
      .readFileSync(storyboard, 'utf8')
      .replace(
        'red="1.000" green="1.000" blue="1.000"',
        'red="1" green="1.0000" blue="1.0000"'
      );
    fs.writeFileSync(storyboard, xml);

    expect(runDoctor(root)).toEqual({ errors: 0, warnings: 0 });
  });

  it('recognizes a fully qualified MainActivity name', () => {
    setupGeneratedProject();
    const manifestFile = path.join(
      root,
      'android',
      'app',
      'src',
      'main',
      'AndroidManifest.xml'
    );
    const manifest = fs
      .readFileSync(manifestFile, 'utf8')
      .replace(
        'android:name=".MainActivity"',
        'android:name="com.example.MainActivity"'
      );
    fs.writeFileSync(manifestFile, manifest);

    expect(runDoctor(root).errors).toBe(0);
  });

  it('catches missing Android theme wiring', () => {
    setupGeneratedProject({ patch: false });
    const report = runDoctor(root);
    expect(report.errors).toBeGreaterThanOrEqual(1);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining(
        'MainActivity does not use @style/SplashMotionTheme'
      )
    );
  });
});

describe('doctor — launch storyboard', () => {
  const storyboardPath = () =>
    path.join(root, 'ios', 'MyApp', 'LaunchScreen.storyboard');

  it('fails when the storyboard is truncated', () => {
    setupGeneratedProject();
    const xml = fs.readFileSync(storyboardPath(), 'utf8');
    fs.writeFileSync(storyboardPath(), xml.slice(0, xml.length / 2));

    const report = runDoctor(root);
    expect(report.errors).toBe(1);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('LaunchScreen.storyboard is not valid XML')
    );
  });

  it('fails when a tag is closed in the wrong order', () => {
    setupGeneratedProject();
    const xml = fs.readFileSync(storyboardPath(), 'utf8');
    fs.writeFileSync(storyboardPath(), xml.replace('</scenes>', '</scene>'));
    expect(runDoctor(root).errors).toBe(1);
  });

  it('warns when the storyboard was not generated by SplashMotion', () => {
    setupGeneratedProject();
    fs.writeFileSync(
      storyboardPath(),
      '<?xml version="1.0"?>\n<document launchScreen="YES"></document>\n'
    );

    const report = runDoctor(root);
    expect(report.errors).toBe(0);
    expect(report.warnings).toBe(1);
    expect(console.log).toHaveBeenCalledWith(
      expect.stringContaining('was not generated by SplashMotion')
    );
  });

  it('does not report both problems for a broken foreign storyboard', () => {
    setupGeneratedProject();
    fs.writeFileSync(storyboardPath(), '<document launchScreen="YES">');
    const report = runDoctor(root);
    expect(report.errors).toBe(1);
    expect(report.warnings).toBe(0);
  });
});

describe('xmlProblem', () => {
  it.each([
    ['a simple document', '<a><b x="1"/><c>text</c></a>'],
    ['a prolog and comments', '<?xml version="1.0"?>\n<!-- hi <b> -->\n<a/>'],
    ['">" inside an attribute', '<a title="x > y"><b/></a>'],
    ['CDATA containing tags', '<a><![CDATA[<b></c>]]></a>'],
    ['a doctype', '<!DOCTYPE a>\n<a/>'],
  ])('accepts %s', (_name, xml) => {
    expect(xmlProblem(xml)).toBeNull();
  });

  it.each([
    ['an unclosed tag', '<a><b></a>', /expected/],
    ['a missing closing tag', '<a><b></b>', /never closed/],
    ['a stray closing tag', '</a>', /unexpected/],
    ['an unterminated tag', '<a><b', /unterminated tag/],
    ['an unterminated comment', '<a><!-- oops </a>', /comment is never closed/],
    ['two root elements', '<a/><b/>', /more than one root/],
    ['no elements', 'just text', /no root element/],
  ])('rejects %s', (_name, xml, message) => {
    expect(xmlProblem(xml)).toMatch(message);
  });
});
