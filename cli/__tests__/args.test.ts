import { describe, expect, it } from 'vitest';
import { nextSteps, parseArgs, parsePlatforms, validateFlags } from '../args';

describe('CLI argument parsing', () => {
  it('parses boolean, negated and valued options', () => {
    const { positional, flags } = parseArgs([
      'generate',
      'splash.json',
      '--force',
      '--no-loop',
      '--loop-marker',
      'loop',
      '--intro-marker',
      'intro',
      '--background=#0F6E56',
    ]);
    expect(positional).toEqual(['generate', 'splash.json']);
    expect(flags).toMatchObject({
      force: true,
      loop: false,
      loopMarker: 'loop',
      introMarker: 'intro',
      background: '#0F6E56',
    });
  });

  it('parses = forms of boolean options', () => {
    expect(parseArgs(['--keep-background=false']).flags.keepBackground).toBe(
      false
    );
    expect(parseArgs(['--force=true']).flags.force).toBe(true);
    expect(() => parseArgs(['--force=yes'])).toThrow(/takes no value/);
  });

  it('recognizes help and version aliases', () => {
    expect(parseArgs(['--version']).flags.version).toBe(true);
    expect(parseArgs(['-v']).flags.version).toBe(true);
    expect(parseArgs(['-h']).flags.help).toBe(true);
  });

  it('rejects a missing option value', () => {
    expect(() => parseArgs(['generate', 'splash.json', '--width'])).toThrow(
      /Missing value for --width/
    );
  });

  it('rejects a misspelled generate option', () => {
    const { flags } = parseArgs(['generate', 'splash.json', '--widht', '200']);
    expect(() => validateFlags('generate', flags)).toThrow(
      /Unknown option "--widht" for "generate"/
    );
  });

  it('accepts all documented generate options', () => {
    const { flags } = parseArgs([
      'generate',
      'splash.json',
      '--background',
      '#FFFFFF',
      '--width',
      '200',
      '--height',
      '100',
      '--still',
      'still.png',
      '--loop-marker',
      'loop',
      '--no-loop',
      '--keep-background',
      '--force',
      '--platforms',
      'ios,android',
      '--project-root',
      '.',
    ]);
    expect(() => validateFlags('generate', flags)).not.toThrow();
  });

  it('rejects generate-only options for doctor', () => {
    const { flags } = parseArgs(['doctor', '--platforms', 'ios']);
    expect(() => validateFlags('doctor', flags)).toThrow(
      /Unknown option "--platforms" for "doctor"/
    );
  });
});

describe('CLI platforms and next steps', () => {
  it('defaults to both platforms', () => {
    expect(parsePlatforms(undefined)).toEqual(['ios', 'android']);
  });

  it('supports one platform and removes duplicates', () => {
    expect(parsePlatforms('android')).toEqual(['android']);
    expect(parsePlatforms('ios,ios')).toEqual(['ios']);
  });

  it.each(['windows', 'ios,', ''])('rejects invalid platforms %j', (value) => {
    expect(() => parsePlatforms(value)).toThrow(/Unknown platform/);
  });

  it('omits pod install for Android-only generation', () => {
    const steps = nextSteps(['android']);
    expect(steps).not.toContain('pod install');
    expect(steps).toContain('1. Rebuild the app');
    expect(steps).toContain('2. Call SplashMotion.hide()');
  });

  it('includes pod install first when iOS is selected', () => {
    const steps = nextSteps(['ios', 'android']);
    expect(steps).toContain('1. cd ios && pod install');
    expect(steps).toContain('3. Call SplashMotion.hide()');
  });
});
