/** Command-line parsing kept separate from the entry point so it is testable. */
import { LOG } from './core';

export type Flags = Record<string, string | boolean | undefined>;
export type Platform = 'ios' | 'android';

const BOOLEAN_FLAGS = new Set(['keep-background', 'force']);
const COMMON_FLAGS = ['help', 'version', 'projectRoot'];
const COMMAND_FLAGS: Record<'generate' | 'doctor', string[]> = {
  generate: [
    ...COMMON_FLAGS,
    'background',
    'width',
    'height',
    'still',
    'loop',
    'loopMarker',
    'timeout',
    'keepBackground',
    'force',
    'platforms',
  ],
  doctor: COMMON_FLAGS,
};

export function parseArgs(argv: string[]) {
  const positional: string[] = [];
  const flags: Flags = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === '-h' || arg === '--help') {
      flags.help = true;
    } else if (arg === '-v' || arg === '--version') {
      flags.version = true;
    } else if (BOOLEAN_FLAGS.has(arg.slice(2).split('=')[0]!)) {
      const [key = '', inline] = arg.slice(2).split('=');
      if (inline !== undefined && inline !== 'true' && inline !== 'false')
        throw new Error(`${LOG} --${key} takes no value (or true/false).`);
      flags[toCamel(key)] = inline !== 'false';
    } else if (arg.startsWith('--no-')) {
      flags[toCamel(arg.slice(5))] = false;
    } else if (arg.startsWith('--')) {
      const [key = '', inline] = arg.slice(2).split('=');
      const value = inline ?? argv[++i];
      if (value === undefined)
        throw new Error(`${LOG} Missing value for --${key}`);
      flags[toCamel(key)] = value;
    } else {
      positional.push(arg);
    }
  }
  return { positional, flags };
}

export function validateFlags(command: 'generate' | 'doctor', flags: Flags) {
  const allowed = new Set(COMMAND_FLAGS[command]);
  const unknown = Object.keys(flags).find((flag) => !allowed.has(flag));
  if (unknown) {
    throw new Error(
      `${LOG} Unknown option "--${toKebab(unknown)}" for "${command}". Run with --help.`
    );
  }
}

export function parsePlatforms(value: Flags['platforms']): Platform[] {
  const platforms = String(value ?? 'ios,android')
    .split(',')
    .map((p) => p.trim());
  const invalid = platforms.find((p) => p !== 'ios' && p !== 'android');
  if (invalid !== undefined) {
    throw new Error(
      `${LOG} Unknown platform "${invalid}". Use --platforms ios,android.`
    );
  }
  return [...new Set(platforms)] as Platform[];
}

export function nextSteps(platforms: Platform[]): string {
  const steps = [
    ...(platforms.includes('ios') ? ['cd ios && pod install'] : []),
    'Rebuild the app (a JS reload is not enough)',
    'Call SplashMotion.hide() when your app is ready',
  ];
  return `\n  Next steps:\n${steps
    .map((step, i) => `    ${i + 1}. ${step}`)
    .join('\n')}\n`;
}

function toCamel(s: string) {
  return s.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}

function toKebab(s: string) {
  return s.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}
