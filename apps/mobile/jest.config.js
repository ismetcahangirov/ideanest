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
   * NOTHING IS OVERRIDDEN HERE, AND THAT IS THE FIX RATHER THAN THE OMISSION.
   *
   * React Native and most of the Expo SDK ship untranspiled and are compiled on
   * the way in by `babel-preset-expo`. `jest-expo`'s preset already carries the
   * pattern for that AND already knows about pnpm — its list begins
   * `/node_modules/(?!(.pnpm|react-native|...`, so every package inside the
   * store is transformed.
   *
   * A hand-written `transformIgnorePatterns` here replaces that wholesale, which
   * is what happened first: the list had to name each package, `standard-navigation`
   * had to be added when Expo Router pulled it in, and scoped packages needed a
   * `+` in the terminator because pnpm writes their directories as
   * `@react-native+jest-preset@0.86.3`. All of that was rebuilding something the
   * preset already had, and getting a different answer from it on a different
   * operating system.
   */
  moduleNameMapper: {
    '^@/(.*)$': path.join(__dirname, 'src/$1'),
  },
  collectCoverageFrom: ['src/**/*.{ts,tsx}', '!src/**/*.test.{ts,tsx}'],
};
