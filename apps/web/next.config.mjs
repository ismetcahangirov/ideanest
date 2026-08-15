/**
 * Deliberately `.mjs` rather than `.ts`: Next compiles a TypeScript config
 * through the `typescript` package it finds in the workspace, and this
 * repository is on TypeScript 7, whose compiler API it cannot drive. A plain
 * module with JSDoc types costs nothing and removes the coupling.
 */

/**
 * Where the Spring Boot service listens. Read at build time on the server only,
 * so it is not a `NEXT_PUBLIC_` variable — the browser never learns the API's
 * real origin, it only ever talks to this application.
 */
const apiOrigin = process.env.IDEANEST_API_ORIGIN ?? 'http://localhost:8080';

/** @type {import('next').NextConfig} */
const nextConfig = {
  /**
   * `@ideanest/ui` and `@ideanest/design-tokens` are source-only packages —
   * their `exports` point straight at `.ts`/`.tsx`. Next has to compile them
   * rather than assume a built `dist`.
   */
  transpilePackages: ['@ideanest/ui', '@ideanest/design-tokens'],

  /**
   * The API is proxied under this application's own origin, and that is not a
   * convenience.
   *
   * The refresh cookie is `SameSite=Strict` on `Path=/v1/auth`, so a browser
   * will not attach it to a request aimed at another origin — which is the
   * whole point of the setting. The service also declares no CORS policy, so a
   * cross-origin call would not survive its preflight either. Same-origin is
   * therefore the only arrangement in which the browser half of the auth flow
   * works at all, and routing `/v1` through here is what makes it same-origin.
   */
  async rewrites() {
    return [{ source: '/v1/:path*', destination: `${apiOrigin}/v1/:path*` }];
  },
};

export default nextConfig;
