/**
 * What every test in this package gets, and why each mock exists.
 *
 * `@testing-library/react-native` is not imported here. Its matchers have been
 * built into the library since v12.4 and the `extend-expect` entry point was
 * removed in v14 -- a setup file that still reaches for it fails every suite
 * with a module-not-found naming a path that reads like a typo.
 */
/**
 * MMKV is a native module. It has no JavaScript fallback by design — that is
 * the point of it — so under Jest it must be replaced rather than loaded.
 *
 * The replacement is a real `Map` and not a stub returning `undefined`, because
 * the code under test is a cache: a store that forgets everything makes an
 * offline test pass for the wrong reason. `src/lib/storage.ts` is the only
 * module that imports MMKV, so this mock has exactly one consumer.
 */
jest.mock('react-native-mmkv', () => ({
  createMMKV: () => {
    const entries = new Map<string, string | number | boolean>();
    return {
      getString: (key: string) => {
        const value = entries.get(key);
        return typeof value === 'string' ? value : undefined;
      },
      set: (key: string, value: string | number | boolean) => void entries.set(key, value),
      remove: (key: string) => entries.delete(key),
      contains: (key: string) => entries.has(key),
      getAllKeys: () => [...entries.keys()],
      clearAll: () => entries.clear(),
    };
  },
}));

/**
 * `expo-glass-effect` is a native view with no JavaScript implementation on iOS,
 * and Expo Router imports it from its native stack — so anything that renders a
 * `Link` reaches it.
 *
 * <p>Its `GlassView.ios.tsx` calls `requireNativeViewManager` at module load and
 * throws "are you sure you've linked all the native dependencies properly?".
 * Which of its two files is resolved depends on the default platform, so a suite
 * without this mock passes on one operating system and fails on another — which
 * is exactly what happened between this machine and the Linux runner.
 *
 * A plain `View` is the honest replacement: that is what the package's own
 * non-iOS implementation is.
 */
jest.mock('expo-glass-effect', () => {
  const { View } = require('react-native');
  return {
    GlassView: View,
    GlassContainer: View,
    isLiquidGlassAvailable: () => false,
    isGlassEffectAPIAvailable: () => false,
  };
});

/**
 * Reanimated is NOT mocked here, and that is deliberate.
 *
 * `react-native-reanimated/mock` re-exports the real module before replacing
 * parts of it, so requiring it pulls in the worklets native binding and throws.
 * `jest.config.js` points Jest at `react-native-worklets/jest/resolver.js`
 * instead, which makes the package resolve to its JavaScript half — so the real
 * Reanimated runs, `FadeInDown` is a real animation object, and a component that
 * misuses one fails here rather than on a device.
 */
