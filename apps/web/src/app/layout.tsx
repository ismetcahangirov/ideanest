import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: {
    default: 'IdeaNest',
    template: '%s · IdeaNest',
  },
};

/**
 * `color-scheme: dark` is declared on `:root` by the token file
 * (docs/ui-kit.md §9.4), so browser chrome, scrollbars, and native controls
 * follow the system without anything further here.
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh bg-surface-1">{children}</body>
    </html>
  );
}
