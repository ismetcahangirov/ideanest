import { Children, isValidElement, cloneElement, type ReactElement, type ReactNode } from 'react';

/**
 * Resolve the async server components in an element tree so a synchronous renderer can draw
 * it — issues #324 and #123.
 *
 * <h2>The problem this solves</h2>
 *
 * A server component may be `async`, and since the shell reads the message catalogue several
 * of them are: `SiteShell` awaits `shellCopy()`, `AdminArea` awaits the skip link's word. In
 * the application Next resolves those during the server render and the browser never sees a
 * promise.
 *
 * `@testing-library/react` renders on the client, through `createRoot`, which cannot. An
 * async component reached that way renders as nothing at all — not an error, **nothing** — so
 * a landmark test asserting `getByRole('banner')` fails with "there are no accessible roles"
 * and an empty `<body><div /></body>`, which reads as the layout being broken rather than as
 * the renderer being unable to await it.
 *
 * <h2>What this does, and what it deliberately does not</h2>
 *
 * It walks the tree, calls any function component whose return value is a promise, awaits it,
 * and recurses — into the result and into `props.children`. Everything else is left exactly
 * as written, so client components still mount normally and hooks still run.
 *
 * It does NOT resolve a component whose async-ness is conditional on props it has not been
 * given, and it does not touch `Suspense` boundaries or lazy components. It is a test
 * convenience for the specific shape this application has — a server frame around a client
 * subtree — and not a general server renderer. Where a test needs the real thing, it should
 * render the page through Next rather than reach for this.
 */
export async function resolveServerTree(node: ReactNode): Promise<ReactNode> {
  if (Array.isArray(node)) {
    return Promise.all(node.map((child) => resolveServerTree(child)));
  }

  if (!isValidElement(node)) return node;

  const element = node as ReactElement<{ children?: ReactNode }>;

  /*
   * ASYNC-NESS IS DETECTED WITHOUT CALLING, which is the whole subtlety of this file.
   * "Call it and see whether it returned a promise" is the obvious implementation and it is
   * wrong: it calls every function component, client ones included, and a client component
   * calls hooks — `SiteHeader` reaches `useParams` on its first line. Outside a render there
   * is no dispatcher, so it throws from inside Next rather than from here, and the stack
   * blames the component instead of the helper that called it.
   *
   * `AsyncFunction` is the constructor of any function declared `async`, which every async
   * server component in this repository is. A component that returned a promise without being
   * declared `async` would be missed — nothing here does that, and the failure would be the
   * empty render this file exists to explain rather than a wrong result.
   */
  if (typeof element.type === 'function' && element.type.constructor.name === 'AsyncFunction') {
    const resolved = await (element.type as (props: unknown) => Promise<ReactNode>)(
      element.props,
    );

    return resolveServerTree(resolved);
  }

  /*
   * A synchronous function component is left alone rather than inlined. Inlining it would
   * mount its subtree here, outside React, so its hooks would never run and a client
   * component below it would render its initial state forever.
   */

  const children = element.props.children;
  if (children === undefined) return element;

  const resolved = await Promise.all(
    Children.toArray(children).map((child) => resolveServerTree(child)),
  );

  return cloneElement(element, undefined, ...resolved);
}
