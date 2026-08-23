/**
 * Loading a provider's SDK, once, when somebody actually wants it.
 *
 * <h2>Why not `next/script`</h2>
 *
 * `next/script` is declarative: the tag is in the tree, so the script is fetched when the
 * component renders. Both of these are fetched **on the first interaction instead** — the
 * Google and Apple SDKs together are a couple of hundred kilobytes of third-party JavaScript
 * that most visitors to the sign-in page will never use, and the two screens they sit on are
 * the ones this application is otherwise most careful about (`app/(auth)` takes the minimal
 * shell for exactly this reason). A promise a click awaits is what makes "do not fetch it
 * until it is wanted" expressible.
 *
 * It also keeps the third-party origins off the critical path of a page whose Largest
 * Contentful Paint is a form.
 *
 * <h2>Once per URL, and shared between callers</h2>
 *
 * The promise is cached, so two clicks do not append two tags and a component that unmounts
 * and remounts does not refetch. A rejection is **not** cached: a script that failed because
 * the network was down should be retryable, and caching the failure would mean the button
 * never works again until a reload.
 */

const pending = new Map<string, Promise<void>>();

export function loadExternalScript(src: string): Promise<void> {
  const cached = pending.get(src);
  if (cached !== undefined) return cached;

  const loading = new Promise<void>((resolve, reject) => {
    if (typeof document === 'undefined') {
      reject(new Error('There is no document to load a script into.'));
      return;
    }

    /*
     * An existing tag is adopted rather than duplicated. It can be there after a client-side
     * navigation back to this page in a build where the module registry was reset but the
     * DOM was not.
     */
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${CSS.escape(src)}"]`);
    if (existing !== null && existing.dataset['loaded'] === 'true') {
      resolve();
      return;
    }

    const tag = existing ?? document.createElement('script');
    tag.src = src;
    tag.async = true;
    /*
     * `crossOrigin` is not set, deliberately. Neither provider serves these files with the
     * CORS headers an `anonymous` request requires, and setting it turns a working script
     * into a network error that reports itself as an ordinary load failure.
     */
    tag.addEventListener('load', () => {
      tag.dataset['loaded'] = 'true';
      resolve();
    });
    tag.addEventListener('error', () => {
      pending.delete(src);
      tag.remove();
      reject(new Error(`The sign-in provider at ${src} could not be reached.`));
    });

    if (existing === null) document.head.append(tag);
  });

  pending.set(src, loading);
  return loading.catch((cause: unknown) => {
    pending.delete(src);
    throw cause;
  });
}

/** For tests, which would otherwise share one module-level cache across cases. */
export function resetLoadedScripts(): void {
  pending.clear();
}
