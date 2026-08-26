import axe, { type AxeResults, type ElementContext, type Result, type RunOptions } from 'axe-core';
import { expect } from 'vitest';

/**
 * An automated accessibility pass over a rendered tree — issue #129.
 *
 * <h2>What this catches, and what it cannot</h2>
 *
 * axe finds the machine-checkable half of §9: an icon-only control with no accessible name, a
 * heading level skipped, a form field with no label, a landmark nested where it may not be, a
 * `role` whose required attributes are missing. Those are the defects that survive review
 * precisely because they are invisible — the screen looks right.
 *
 * <p>It cannot check the other half, and this helper does not pretend otherwise. Focus
 * <em>order</em>, whether a keyboard reader can reach a control at all, and whether a name is
 * the <em>right</em> name are all judgements, and they stay as ordinary assertions in the
 * suites beside this. So does contrast: jsdom computes no styles, so every colour rule axe
 * has is inert here, and the real check is `packages/ui`'s token test plus review in
 * Storybook, which `docs/ui-kit.md` §9.4 already names as where appearance is judged.
 *
 * <h2>Why the rules are named rather than left at the default set</h2>
 *
 * The default set includes rules about the whole document — one `<main>`, a page title, a
 * language on `<html>` — and every one of these tests renders a fragment into a bare `<div>`.
 * A fragment failing "the page has no `<h1>`" is a true statement about a `<div>` and says
 * nothing about the application, and a suite full of those is a suite people learn to ignore.
 * The document-level rules belong to the layout, which `AccountArea.test.tsx` and the shell's
 * own tests already assert directly.
 */

/**
 * The rules a rendered fragment is judged by.
 *
 * WCAG 2.2 A and AA, which is what `docs/ui-kit.md` §9 commits to, minus the checks that
 * describe a whole document. `best-practice` is deliberately included: "an interactive
 * element must have an accessible name" is technically a best practice in axe's taxonomy and
 * is the single most useful rule here, since icon-only controls are where this platform's
 * risk actually is.
 */
const RULES: RunOptions = {
  runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] },
  rules: {
    /*
     * Rules about a document, disabled because these render fragments. Each one is checked
     * where it belongs — the shell owns the landmarks and the layout owns `lang` and the
     * title.
     */
    'page-has-heading-one': { enabled: false },
    region: { enabled: false },
    'landmark-one-main': { enabled: false },
    'html-has-lang': { enabled: false },
    'document-title': { enabled: false },
    'bypass': { enabled: false },
    /*
     * Colour, disabled because jsdom computes no styles: every element is transparent on
     * transparent, so this rule either passes vacuously or fails on a value no browser would
     * ever produce. `packages/ui`'s token test is what enforces the palette, and
     * `docs/ui-kit.md` §9.4 makes contrast a build error at the token level rather than at
     * the render.
     */
    'color-contrast': { enabled: false },
  },
};

/**
 * Fails the test with every violation named, rather than with a count.
 *
 * The message carries the rule, its help text, and the markup of each offending node, because
 * "2 accessibility violations" is a failure somebody has to reproduce before they can fix it.
 */
export async function expectNoViolations(
  context: ElementContext,
  options: RunOptions = {},
): Promise<void> {
  const results: AxeResults = await axe.run(context, { ...RULES, ...options });

  expect(describe(results.violations), 'accessibility violations').toBe('');
}

function describe(violations: readonly Result[]): string {
  return violations
    .map((violation) => {
      const nodes = violation.nodes.map((node) => `      ${node.html}`).join('\n');
      return `  ${violation.id}: ${violation.help}\n${nodes}`;
    })
    .join('\n');
}
