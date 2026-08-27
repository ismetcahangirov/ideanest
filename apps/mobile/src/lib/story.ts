/**
 * A campaign story, flattened to something a phone can render.
 *
 * <h2>Why the story is not rendered as rich text here</h2>
 *
 * `ProjectPageResponse.story` is a TipTap document — the JSON shape §14.2's
 * editor produces. `apps/web` renders it with TipTap's own renderer, which is a
 * browser library and has no React Native equivalent that shares its node types.
 * The alternatives were to ship a second renderer with its own idea of what a
 * heading is, or to render the text and be honest that formatting is missing.
 *
 * The second is chosen, and the reason is what a story is used for on a phone:
 * somebody deciding whether to back something reads it once, in a scroll view,
 * on the way somewhere. Losing bold is a cost. A renderer that disagrees with
 * the web about list nesting would be a cost paid on every campaign, invisibly,
 * with nothing to compare against.
 *
 * When the mobile checkout (#58) makes this screen a purchase surface rather
 * than a reading one, a shared renderer becomes worth building. It is not one
 * yet, and this file says so in one place rather than each screen guessing.
 */

/** The subset of a TipTap document this needs to walk. Nothing here validates it. */
interface Node {
  readonly type?: string;
  readonly text?: string;
  readonly content?: readonly Node[];
}

/** Node types that end a block, and therefore a paragraph on screen. */
const BLOCK_TYPES = new Set([
  'paragraph',
  'heading',
  'blockquote',
  'listItem',
  'codeBlock',
  'horizontalRule',
]);

/**
 * The story as paragraphs of plain text.
 *
 * Empty blocks are dropped rather than rendered as blank space: a TipTap
 * document routinely carries a trailing empty paragraph, and three of them at
 * the end of every campaign is a scroll view that looks broken.
 *
 * @param story the `story` field, whatever it happens to be — this is called
 *     with data from the network and must not throw on a shape it did not expect
 */
export function storyParagraphs(story: unknown): string[] {
  const paragraphs: string[] = [];
  let current = '';

  const walk = (node: Node): void => {
    if (typeof node.text === 'string') {
      current += node.text;
    }

    for (const child of node.content ?? []) {
      walk(child);
    }

    if (node.type !== undefined && BLOCK_TYPES.has(node.type)) {
      const trimmed = current.trim();
      if (trimmed !== '') paragraphs.push(trimmed);
      current = '';
    }
  };

  if (story !== null && typeof story === 'object') {
    walk(story as Node);
  }

  const trailing = current.trim();
  if (trailing !== '') paragraphs.push(trailing);

  return paragraphs;
}
