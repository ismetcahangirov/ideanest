import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';

/**
 * `@ideanest/ui/server` really is server-safe.
 *
 * <h2>What this is guarding against</h2>
 *
 * The entry point exists so that a React Server Component can use the kit's stateless
 * components without dragging the barrel's client-only members into the server graph. Its
 * whole value is the promise that nothing behind it touches a hook, a context, or
 * `motion/react` — and that promise is one `useState` away from being false.
 *
 * <strong>The failure is not a compile error where the mistake is.</strong> Adding state to
 * `Tag` compiles fine, passes every one of its own tests, and breaks `next build` in
 * `apps/web` with a message naming a page that never imported `Tag`. Somebody would then
 * either find this file or, far more likely, mark the offending component `'use client'` —
 * which makes it a client boundary for every Server Component that imports it, silently, and
 * whose first symptom is a page whose First Load JS grew for no visible reason.
 *
 * So the rule is checked here, in the package that makes the promise, where the failure
 * names the component.
 *
 * <h2>Transitively</h2>
 *
 * Every module reachable from the entry point by a relative import is scanned, not just the
 * ones it names. A component is server-safe only if everything it pulls in is, and `cn` and
 * the token package are exactly the kind of shared helper that would acquire a hook without
 * anybody thinking of this list.
 */

const ENTRY = join(import.meta.dirname, 'server.ts');

/**
 * What makes a module client-only.
 *
 * `use client` is included deliberately even though it would *fix* the build error: a
 * component in this entry point that needs the directive is a component that no longer
 * belongs in it, and accepting the directive here would turn a curated list of stateless
 * components into an ordinary barrel with extra steps.
 */
const CLIENT_ONLY =
  /\b(useState|useEffect|useLayoutEffect|useRef|useReducer|useContext|createContext|useId|useSyncExternalStore|useTransition)\b|['"]use client['"]|from ['"]motion\/react['"]/;

/** Every module the entry point reaches by a relative import, including itself. */
function reachable(entry: string): string[] {
  const seen = new Set<string>();
  const queue = [entry];

  while (queue.length > 0) {
    const file = queue.pop() as string;
    if (seen.has(file)) continue;
    seen.add(file);

    const source = readFileSync(file, 'utf8');
    for (const match of source.matchAll(/from\s+['"](\.[^'"]+)['"]/g)) {
      const target = resolveModule(dirname(file), match[1] as string);
      if (target !== null) queue.push(target);
    }
  }
  return [...seen];
}

/** A relative specifier as a file on disk, or null when it is not one of ours. */
function resolveModule(from: string, specifier: string): string | null {
  const base = resolve(from, specifier);
  for (const candidate of [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')]) {
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

describe('the server entry point', () => {
  const modules = reachable(ENTRY);

  it('reaches the components it exports', () => {
    // A resolver that silently found nothing would make every assertion below vacuous.
    expect(modules.length).toBeGreaterThan(5);
    expect(modules.some((file) => file.endsWith('ProgressBar.tsx'))).toBe(true);
    expect(modules.some((file) => file.endsWith('Media.tsx'))).toBe(true);
  });

  it.each(modules.map((file) => [relative(import.meta.dirname, file), file]))(
    'has no client-only dependency: %s',
    (_label, file) => {
      const source = readFileSync(file, 'utf8')
        // Comments are stripped so that a paragraph explaining why a component does NOT use
        // `useState` does not fail the rule it is describing.
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/^\s*\/\/.*$/gm, '');

      expect(
        CLIENT_ONLY.test(source),
        `${_label} is reachable from @ideanest/ui/server and uses a client-only API. ` +
          `Either keep it stateless, or remove it from src/server.ts — do not add 'use client'.`,
      ).toBe(false);
    },
  );

  /**
   * And the counterpart: the entry point is not the barrel with a different name.
   *
   * Re-exporting everything would make it pass the check above only until somebody added a
   * stateful component, and would defeat the point of having two entry points at all.
   */
  it('is a subset of the barrel rather than a copy of it', () => {
    const server = readFileSync(ENTRY, 'utf8');
    const barrel = readFileSync(join(import.meta.dirname, 'index.ts'), 'utf8');

    expect(exportedNames(server).length).toBeLessThan(exportedNames(barrel).length);
    // Everything it exports, the barrel exports too: one component, never two versions.
    for (const name of exportedNames(server)) {
      expect(barrel, `${name} is in the server entry point and not in the barrel`).toContain(name);
    }
  });
});

function exportedNames(source: string): string[] {
  const names: string[] = [];
  for (const match of source.matchAll(/export\s*\{([^}]*)\}/g)) {
    for (const part of (match[1] as string).split(',')) {
      const name = part.replace(/\btype\b/, '').trim();
      if (name !== '') names.push(name);
    }
  }
  return names;
}
