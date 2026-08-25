import { redirect as nextRedirect } from 'next/navigation';
import type { Locale } from '../lib/i18n/locale';

/**
 * `redirect()` for a server component, with the language kept — issue #123.
 *
 * <h2>Why this is a separate file from `navigation.tsx`</h2>
 *
 * That module is `'use client'`, because `Link` and the hooks are. Importing `redirect` from
 * it would pull a client boundary into the three server components that call it — each of
 * which renders nothing at all and exists only to redirect — and would put React hooks into a
 * module that runs during a server render. Fifteen lines in their own file is the cheaper
 * answer.
 *
 * <h2>The locale is a parameter and is not optional</h2>
 *
 * There is nothing to read it from here: no rendered subtree, no params hook, and reading the
 * cookie would be both wrong (the URL is what decides the language now) and expensive (it
 * would make the caller dynamic). Every caller already has it — it is the segment their own
 * route matched — so asking for it costs nothing and removes the failure where a redirect
 * silently defaults to English on a page with no visible output to notice it on.
 */
export function localeRedirect(href: string, locale: Locale): never {
  /*
   * `redirect()` works by throwing, so this never returns — which is why the signature says
   * so rather than `void`. A caller that wrote `return localeRedirect(...)` and a caller that
   * wrote it as a statement both behave identically, and TypeScript knows the code after it
   * is unreachable.
   */
  nextRedirect(href === '/' ? `/${locale}` : `/${locale}${href}`);
}
