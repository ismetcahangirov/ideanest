import { createRequire } from 'node:module';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const require = createRequire(import.meta.url);

/**
 * Where `next/navigation` and `next/server` live, resolved from this package.
 *
 * <h2>Why this alias exists — issue #123</h2>
 *
 * `next-intl` imports `next/navigation` and `next/server` from inside its own package
 * directory. Under pnpm that directory is a symlink into `.pnpm/next-intl@…`, and Vite
 * resolves a bare specifier relative to the *real* path rather than the link, so it looks for
 * `next` beside `next-intl` and does not find it: this workspace hoists `next` to the
 * application, not into next-intl's store folder.
 *
 * The application itself never hit this, because `next build` does its own resolution. It
 * appeared the moment `i18n/navigation.ts` became something the unit tests import — 53 test
 * files stopped loading at once with `Cannot find module …/next-intl/node_modules/next/navigation`.
 *
 * Naming the files explicitly is the fix rather than a workaround: `require.resolve` runs
 * from THIS package, where `next` is a direct dependency and always resolvable, and it pins
 * the same copy the application bundles. Two copies of Next's navigation module in one test
 * run would give a component and its test different `usePathname` identities, which fails as
 * a null return rather than as an error.
 */
const nextPackage = (subpath: string): string => require.resolve(`next/${subpath}`);

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      'next/navigation': nextPackage('navigation'),
      'next/server': nextPackage('server'),
    },
  },
  test: {
    /*
     * `next-intl` must be transformed by Vite rather than loaded by Node directly, or the
     * alias above never applies: an externalised dependency is resolved by Node's own ESM
     * loader, which knows nothing about `resolve.alias` and reports the failure from inside
     * next-intl's store directory. Inlining it is what puts the import through Vite.
     */
    server: { deps: { inline: ['next-intl'] } },
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
