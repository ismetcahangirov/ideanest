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
 * Expo Router, replaced by the three things the components under test use.
 *
 * <h2>Why the real one is not loaded</h2>
 *
 * Importing it pulls in the whole native stack — the toolbar, the glass effect,
 * the screen container — and several of those call
 * `requireNativeViewManager` AT MODULE LOAD. Which variant of each file resolves
 * depends on the default platform, so the suite passed on Windows and failed on
 * the Linux runner with `expo-modules-core.requireNativeViewManager is not
 * available on ios`. Mocking one module at a time was whack-a-mole through that
 * tree: `expo-glass-effect` was replaced and `expo-router/toolbar` failed next.
 *
 * <h2>It makes the tests better rather than merely greener</h2>
 *
 * A test of `ProjectCard` is a test of a card. Rendering the real navigator to
 * assert an accessible name is rendering a navigation container to check a
 * string, and it asserted nothing about the destination — the `href` went
 * unchecked because there was nothing to read it off.
 *
 * The `Link` below is a host element carrying its `href`, so a test can assert
 * where a card points. `campaign-list.test.tsx` does.
 */
jest.mock('expo-router', () => {
  const { View } = require('react-native');
  const React = require('react');

  /*
   * Declared INSIDE the factory. Jest refuses a factory that reaches an
   * out-of-scope variable — the guard against a mock referring to something the
   * module registry has not initialised yet — and the error names the variable
   * rather than the rule, which is a minute of confusion the first time.
   *
   * An `href` is either a path or `{ pathname, params }`, and a test that had to
   * know which would be coupled to the call site rather than to the destination.
   */
  const hrefOf = (href: unknown): string => {
    if (typeof href === 'string') return href;
    if (href !== null && typeof href === 'object') {
      const { pathname, params } = href as { pathname?: string; params?: Record<string, string> };
      let path = pathname ?? '';
      for (const [name, value] of Object.entries(params ?? {})) {
        path = path.replace(`[${name}]`, value);
      }
      return path;
    }
    return '';
  };

  return {
    /**
     * A `View` carrying the destination. `asChild` is accepted and ignored: what
     * it changes is which element receives the press, and no test here presses.
     */
    Link: ({ children, href, asChild: _asChild, ...rest }: Record<string, unknown>) =>
      React.createElement(View, { ...rest, testID: 'link', accessibilityValue: { text: hrefOf(href) } }, children),
    Stack: Object.assign(() => null, { Screen: () => null }),
    Tabs: Object.assign(() => null, { Screen: () => null }),
    useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
    useLocalSearchParams: () => ({}),
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
