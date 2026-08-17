import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { SITE_LANGUAGE, rootMetadata } from '../lib/seo/metadata';
import './globals.css';

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
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang={SITE_LANGUAGE}>
      <body className="min-h-dvh bg-surface-1">{children}</body>
    </html>
  );
}
