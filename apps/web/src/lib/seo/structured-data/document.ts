/**
 * THE ENVELOPE EVERY CLAIM TRAVELS IN, and the one place it is escaped.
 *
 * The rest of this directory decides what the site says about itself to a
 * machine; this module decides how that reaches the document. There are two
 * decisions in it and both are security decisions rather than formatting ones.
 *
 * <h2>One `<script>`, one `@graph`</h2>
 *
 * A page could carry a block per type — an `Organization` here, a `Product`
 * there — and every parser would read them. It would also let two blocks on the
 * same page describe the same thing under different identifiers, which is
 * exactly the failure `lib/seo/metadata.ts` exists to prevent for `<meta>` tags.
 * One document, one context, one array of nodes: a node that means to refer to
 * another refers to its `@id` rather than restating it, so there is one
 * `Organization` on the site and everything else points at it.
 *
 * <h2>Why the escaping is `<` and nothing else</h2>
 *
 * The payload carries a creator's campaign title and a creator's reward
 * description, both typed into a form. `JSON.stringify` escapes what JSON
 * requires and nothing else, so `</script>` survives it intact — and inside a
 * `<script>` element the HTML parser is looking for exactly that string. A title
 * of `</script><img src=x onerror=…>` would close the block and run the rest as
 * markup.
 *
 * Escaping `<` closes both ways out of the element, because both begin with one:
 * `</script` ends it and `<!--` switches the tokeniser into a state where it can
 * be ended somewhere unexpected. `<` is a JSON string escape, so a parser
 * reads back the character the creator typed — the value is unchanged, only its
 * spelling in the document is. `&`, `>` and quotes are NOT escaped, because a
 * `<script>` element has no entity references to be confused by and escaping
 * them would be cargo rather than defence.
 *
 * This is also what the framework documents for the App Router
 * (`docs/01-app/02-guides/json-ld.mdx`), which recommends a native `<script>`
 * tag with the same substitution and warns that `JSON.stringify` alone is an XSS
 * hole. No library is added for it: the whole of the sanitiser is one
 * replacement, and a dependency here would be a supply chain in the critical
 * path of every rendered page.
 */

export const SCHEMA_CONTEXT = 'https://schema.org';

/** Anything JSON-LD can hold. Deliberately not `any` — CLAUDE.md §3. */
export type JsonLdValue =
  | string
  | number
  | boolean
  | readonly JsonLdValue[]
  | { readonly [property: string]: JsonLdValue | undefined };

/**
 * One node of the graph.
 *
 * A type alias rather than an `interface` on purpose: TypeScript gives an alias
 * of an object type an implicit index signature and an interface none, so every
 * node shape below would otherwise need a cast to be put in the same array.
 */
export type JsonLdNode = { readonly [property: string]: JsonLdValue | undefined };

/**
 * The same object without the properties that have no value.
 *
 * A DROPPED PROPERTY AND A `null` ONE ARE NOT THE SAME CLAIM. `JSON.stringify`
 * already omits `undefined`, so this would be redundant if the graph were only
 * ever serialised — but every builder here is unit tested against the object it
 * produces, and a test that compares `{ name: 'x', logo: undefined }` to
 * `{ name: 'x' }` passes while the two are different shapes to a reviewer
 * reading the assertion. Removing the key makes the tested object and the
 * emitted document the same thing.
 *
 * Only `undefined` is dropped. `0`, `false` and `''` are values somebody chose.
 */
export function withoutAbsent(node: JsonLdNode): JsonLdNode {
  return Object.fromEntries(
    Object.entries(node).filter(([, value]) => value !== undefined),
  ) as JsonLdNode;
}

/**
 * The graph as a string that is safe to put inside a `<script>` element, or
 * `null` when there is nothing to say.
 *
 * `null` RATHER THAN AN EMPTY GRAPH. A page with no claims should carry no
 * block: `{"@context":…,"@graph":[]}` is a well-formed document that asserts
 * nothing, and every validator that reads it will report it as an empty result
 * that somebody then has to investigate.
 */
export function serialiseStructuredData(nodes: readonly JsonLdNode[]): string | null {
  if (nodes.length === 0) return null;

  const document = { '@context': SCHEMA_CONTEXT, '@graph': nodes };

  return JSON.stringify(document).replaceAll('<', '\\u003c');
}
