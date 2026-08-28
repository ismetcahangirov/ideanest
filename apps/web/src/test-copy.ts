import MESSAGES from '../messages/en.json';

/**
 * The English catalogue, as the lookup a `*-copy.ts` builder takes — issue #324.
 *
 * <h2>Why a test builds the copy instead of typing the words</h2>
 *
 * Every client component that draws words is handed them as a prop, resolved on the server
 * (`lib/i18n/shell-copy.ts` carries the measurement that decided it). A test therefore has to
 * supply that object, and there are two ways to do it: retype the sentences, or build them
 * from the catalogue with the same function the page calls.
 *
 * Retyping gives a test that passes whatever the catalogue says. It would still be green with
 * the message file empty, which is precisely the defect `catalogue.test.ts` and
 * `account-area.pages.test.ts` exist to catch. So the builders are run over `messages/en.json`
 * here, and an assertion is made against the words the application will actually draw.
 *
 * <h2>English, deliberately, and only English</h2>
 *
 * A test that asserted in four languages would be asserting that a translation exists, which
 * `catalogue.test.ts` already does for every key at once. What a component test is for is the
 * wiring — that the button asks for `submit` rather than carrying a literal — and one language
 * shows that as well as four while keeping the assertions readable to everybody reviewing them.
 *
 * <p>`raw` is next-intl's own escape hatch for a message that is not a string, and a couple of
 * builders use it. It is here so a test's lookup has the same surface as the real one.
 */
export function translatorFor(namespace: string): {
  (key: string): string;
  raw(key: string): unknown;
} {
  function at(key: string): unknown {
    let node: unknown = MESSAGES;
    for (const segment of `${namespace}.${key}`.split('.')) {
      node = (node as Record<string, unknown>)[segment];
    }
    return node;
  }

  return Object.assign(
    (key: string): string => {
      const message = at(key);
      /* Throwing names the missing key. Returning `undefined` would fail three frames away. */
      if (typeof message !== 'string') throw new Error(`no message at ${namespace}.${key}`);
      return message;
    },
    { raw: at },
  );
}
