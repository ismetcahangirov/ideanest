import type { Metadata } from 'next';
import { Inter } from 'next/font/google';
import type { ReactNode } from 'react';
import { WebVitals } from '../components/rum/WebVitals';
import { SessionProvider } from '../components/session/SessionProvider';
import { SITE_LANGUAGE, rootMetadata } from '../lib/seo/metadata';
import './globals.css';

/**
 * The body and numeral face (docs/ui-kit.md §5.1). Until this existed the token
 * file named `'Inter'` in `--font-sans` and nothing ever served it, so every
 * surface fell through to `system-ui` — Segoe UI on Windows, Roboto on Android.
 * The type scale and the negative tracking in §5.3 were measured against Inter's
 * metrics, so the product was rendering a design nobody had approved.
 *
 * `next/font` self-hosts the file out of `/_next/static/media` at build time.
 * That is not only a request saved: a `fonts.googleapis.com` stylesheet is a
 * blocking round trip to a third origin before the browser even learns which
 * `.woff2` to ask for, and it hands a third party a log line per visitor.
 *
 * SUBSETS. `latin` and `latin-ext`, and deliberately not the `cyrillic`,
 * `cyrillic-ext`, `greek`, `greek-ext` or `vietnamese` cuts Google also
 * publishes — nothing in this product is written in them, and each one is
 * another file preloaded against the largest contentful paint.
 *
 * Both of the two are needed, which is worth stating because dropping
 * `latin-ext` looks free and is not. Azerbaijani needs `ə ğ ı ö ş ü ç` and
 * their capitals; Google's `latin` cut carries `ı` (U+0131), `ö`, `ü` and `ç`,
 * but `ə` (U+0259), `Ə` (U+018F), `ğ`, `Ğ`, `ş`, `Ş` and `İ` all sit in
 * `U+0100-02BA`, which only `latin-ext` declares. A missing `ə` is a broken
 * product in this market, and `font-subsets.test.ts` fails if this list stops
 * covering those code points.
 *
 * `display: 'swap'` because text that is invisible for three seconds is worse
 * than text in the wrong face for one. `preload` and `adjustFontFallback` are
 * left at their defaults, which are both `true`: Next emits
 * `<link rel="preload">` for the two subset files — the only preloads on the
 * page, and they are on the critical path because every glyph above the fold is
 * set in this family — and it synthesises a metric-matched local fallback so the
 * swap does not move the layout. That fallback is what keeps cumulative layout
 * shift under the 0.05 that docs/motion-system.md §8 asks for.
 *
 * Only `--font-sans` is bound here. `--font-display` still asks for General
 * Sans first (docs/ui-kit.md §5.1's first choice), which is a Fontshare licence
 * and a vendored binary rather than a build-time download; until somebody makes
 * that call it resolves to Inter, which is exactly the substitution §5.1 names.
 */
const inter = Inter({
  subsets: ['latin', 'latin-ext'],
  display: 'swap',
  variable: '--font-inter',
});

/**
 * The defaults every page inherits — the title template, the site description,
 * `metadataBase`, and the Open Graph and X blocks. `lib/seo/metadata.ts` holds
 * them and explains each one; in particular it explains why there is deliberately
 * NO canonical here.
 */
export const metadata: Metadata = rootMetadata();

/**
 * `color-scheme: dark` is declared on `:root` by the token file
 * (docs/ui-kit.md §9.4), so browser chrome, scrollbars, and native controls
 * follow the system without anything further here.
 *
 * `lang` comes from the same constant `og:locale` is built from, so the language
 * this page announces to a screen reader and the one it announces to an unfurler
 * cannot drift apart (docs/architecture.md §21.1).
 *
 * The font variable class goes on `<html>` rather than `<body>` so that
 * `--font-inter` is in scope for anything portalled to `document.body` — every
 * overlay in the kit renders there, and a modal in a different typeface than the
 * page under it is the kind of defect nobody reports and everybody sees.
 *
 * `<WebVitals />` renders nothing at all — it is a client boundary that
 * subscribes to the browser's performance observers and returns `null`, so it
 * adds no element, no text and no layout. It is here rather than in a page
 * because a field measurement of one route is not a measurement; see
 * `docs/observability/real-user-monitoring.md`. It sits before `{children}` so
 * that the boundary is established before anything that could throw inside it.
 *
 * `<SessionProvider>` IS HERE AND NOT IN `app/(site)/layout.tsx`, which is the decision #267
 * turns on. Three groups of routes need to know who is reading — the public shell, the
 * authentication screens, and every private route the guard covers — and they do not share a
 * layout below this one. A provider per group would mean the session is bootstrapped again on
 * every navigation between them, which is a `/v1/auth/refresh` per crossing, and refresh
 * tokens rotate: two of those racing is precisely what the single-flight in
 * `lib/api/access-token.ts` exists to prevent. One provider, at the root, read once.
 *
 * It renders `{children}` unchanged and adds no element of its own. What it costs every route
 * is a context and one effect; what it buys is that no page has to remember to guard itself.
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang={SITE_LANGUAGE} className={inter.variable}>
      <body className="min-h-dvh bg-surface-1">
        <WebVitals />
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  );
}
