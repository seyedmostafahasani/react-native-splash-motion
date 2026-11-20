#!/usr/bin/env node
import fs from 'fs';
import path from 'path';
import * as core from './core';
import { runDoctor } from './doctor';
import { nextSteps, parseArgs, parsePlatforms, validateFlags } from './args';

const HELP = `
  Usage: npx splash-motion generate <animation> [options]

  Generates everything your app needs to show a native animated splash.
  <animation> is a Lottie .json or a dotLottie .lottie file.

  Options:
    --background <hex>        Background color              (default: #FFFFFF)
    --width <number>          Animation width in dp/pt      (default: 160)
    --height <number>         Animation height in dp/pt     (default: keeps aspect ratio)
                              Scaled down at runtime to fit 90% of the screen
    --still <png>             Your animation's first frame, shown by the system
                              launch screen (iOS + Android) before the app starts
    --loop-marker <name>      Only loop this marker's range
    --intro-marker <name>     Play this marker once before the loop starts
    --no-loop                 Play once and hold the last frame
    --keep-background         Keep a full-canvas background solid in the
                              animation (removed by default)
    --force                   Replace the iOS launch storyboard without keeping a
                              .bak copy of one SplashMotion didn't generate
    --platforms <list>        ios,android                   (default: both)
    --project-root <path>     Your app's root folder        (default: current directory)
    --version                 Print the installed splash-motion version

  Example:
    npx splash-motion generate assets/splash.json --background "#0F6E56" --width 200

  npx splash-motion doctor [--project-root <path>]

  Checks the generated setup: assets, launch-screen wiring, marker names
  and common conflicts. Exits non-zero when something is broken.
`;

// lib/cli/index.js sits two levels below the package root.
const { version: packageVersion } = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8')
) as { version: string };

function main() {
  const { positional, flags } = parseArgs(process.argv.slice(2));
  const [command, source] = positional;

  if (flags.version) {
    console.log(packageVersion);
    return;
  }
  if (flags.help || !command) {
    console.log(HELP);
    return;
  }
  if (command !== 'generate' && command !== 'doctor') {
    throw new Error(
      `${core.LOG} Unknown command "${command}". Run with --help.`
    );
  }
  validateFlags(command, flags);

  const projectRoot = path.resolve(
    typeof flags.projectRoot === 'string' ? flags.projectRoot : process.cwd()
  );

  if (command === 'doctor') {
    const report = runDoctor(projectRoot);
    if (report.errors > 0) process.exitCode = 1;
    return;
  }

  const platforms = parsePlatforms(flags.platforms);
  // Flags are untyped argv strings; resolveOptions validates their values.
  const options = core.resolveOptions(projectRoot, {
    ...flags,
    source,
  } as core.SplashOptions);

  console.log(
    `\n✨ SplashMotion — Lottie splash (${options.width}×${options.height})\n`
  );

  if (platforms.includes('ios')) {
    const { storyboard, backup } = core.generateIos(projectRoot, options);
    console.log(
      `  ✓ iOS      assets + ${path.relative(projectRoot, storyboard)}`
    );
    if (backup) {
      console.log(
        `             kept your previous launch screen as ${path.relative(projectRoot, backup)}`
      );
    }
  }
  if (platforms.includes('android')) {
    core.generateAndroidFiles(projectRoot, options);
    core.patchAndroidProject(projectRoot);
    console.log('  ✓ Android  assets, colors, theme and manifest');
  }

  console.log(nextSteps(platforms));
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}
