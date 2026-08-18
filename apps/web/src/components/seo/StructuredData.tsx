import type { ReactElement } from 'react';
import { serialiseStructuredData, type JsonLdNode } from '../../lib/seo/structured-data/document';

/**
 * The one place JSON-LD reaches the document.
 *
 * <h2>A native `<script>`, not `next/script`</h2>
 *
 * `next/script` exists to decide WHEN JavaScript executes — before hydration,
 * after it, lazily. This is not JavaScript; it is a data block that a parser
 * reads out of the served HTML and that a browser never executes. Loading it
 * through a strategy would at best do nothing and at worst move it out of the
 * server-rendered response, which is the only place a crawler looks. The
 * framework's own guidance says the same (`docs/01-app/02-guides/json-ld.mdx`).
 *
 * <h2>No `'use client'`, deliberately</h2>
 *
 * This renders on the server and never again. Structured data that a client
 * component inserted would be absent from the document a crawler is served, and
 * every fact in it is already known at render time — there is nothing to hydrate
 * and nothing to wait for.
 *
 * <h2>`dangerouslySetInnerHTML`, and why it is safe here</h2>
 *
 * React escapes text children for HTML, and `<script>` is the one element where
 * that is wrong: `&lt;` inside a script block is the four characters, not a `<`,
 * so an escaped payload is a JSON document no parser can read. The content
 * therefore has to be written raw, and `serialiseStructuredData` is what makes
 * raw safe — it escapes every `<` so that a creator's campaign title cannot
 * close the element. That function has the full argument and the tests.
 *
 * Nothing renders when there is nothing to claim: an empty graph is a block a
 * validator reports and somebody investigates.
 */
export function StructuredData({ nodes }: { nodes: readonly JsonLdNode[] }): ReactElement | null {
  const json = serialiseStructuredData(nodes);
  if (json === null) return null;

  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: json }} />;
}
