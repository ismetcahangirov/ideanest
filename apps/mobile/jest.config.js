/**
 * `jest-expo`, and why this package alone in the repository is not on vitest.
 *
 * Every other workspace runs vitest, and that is still the right default. React
 * Native is the exception for a mechanical reason rather than a preference: its
 * source ships untranspiled, with Flow annotations, and the transform that
 * strips them is `babel-preset-expo` — the same one Metro uses. A runner that
 * does not go through Babel meets `import type Foo from ...` in
 * `react-native/index.js` and stops at the first import of the framework.
 * `jest-expo` is that Babel transform plus the platform mocks, maintained by the
 * people who ship the SDK.
 */
const path = require('node:path');

module.exports = {
  preset: 'jest-expo',
  setupFilesAfterEnv: [path.join(__dirname, 'jest.setup.ts')],
  /*
   * Reanimated 4 sits on `react-native-worklets`, whose entry point is a
   * `.native.ts` file that reaches straight for the native module and throws
   * outside an application. The package ships this resolver for the purpose: it
   * drops the `native` extension when resolving anything inside it, so Jest gets
   * the JavaScript half. Without it every suite that renders an animated
   * component fails at import with `Cannot read properties of undefined
   * (reading 'loadUnpackers')`, which names nothing anybody wrote.
   */
  resolver: require.resolve('react-native-worklets/jest/resolver.js'),
  /*
   * WHAT HAS TO BE TRANSFORMED, AND WHY THE PRESET'S OWN LIST IS NOT ENOUGH.
   *
   * React Native and most of the Expo SDK ship untranspiled — Flow annotations
   * and ESM — and are compiled by `babel-preset-expo` on the way in. `jest-expo`
   * carries a pattern for that, written against a flat `node_modules`. pnpm does
   * not flatten: every package lives at
   * `node_modules/.pnpm/<name>@<version>/node_modules/<name>`, which that pattern
   * does not reach, so nothing is transformed and the first import of the
   * framework fails with `Cannot use import statement outside a module`.
   *
   * The list is by SCOPE rather than by package, so that a transitive dependency
   * of Expo Router — `standard-navigation` was the one that found this — does not
   * have to be added the day it appears. Everything named here is part of the
   * React Native toolchain and ships source; nothing else in the store is
   * touched, which is what keeps a cold CI run from transforming the world.
   *
   * The terminator is `[@/+]`, and the `+` is not decoration: pnpm writes a
   * scoped package's store directory as `@react-native+jest-preset@0.86.3`, so a
   * pattern that only accepted `@` or `/` after the scope silently excluded every
   * scoped package in the toolchain.
   */
  transformIgnorePatterns: [
    'node_modules/(?!(?:\.pnpm/)?(?:' +
      [
        '(?:jest-)?react-native',
        'react-native-.*',
        '@react-native(?:-community)?',
        '@react-navigation',
        'standard-navigation',
        'expo',
        'expo-.*',
        '@expo',
        '@shopify',
        '@tanstack',
        '@testing-library',
      ].join('|') +
      ')(?:[@/+]|$))',
  ],
  moduleNameMapper: {
    '^@/(.*)$': path.join(__dirname, 'src/$1'),
  },
  collectCoverageFrom: ['src/**/*.{ts,tsx}', '!src/**/*.test.{ts,tsx}'],
};
