import type { JsonLdNode } from './document';

/**
 * The campaign's own questions and answers.
 *
 * <h2>What was checked before writing this, because the answer changed twice</h2>
 *
 * `FAQPage` used to produce a rich result — the expandable questions under a
 * search listing. In August 2023 Google restricted it to well-known
 * authoritative government and health sites, which IdeaNest is not. The
 * remaining eligibility was then withdrawn entirely: the feature stopped
 * appearing in Search on 7 May 2026 and the documentation for it has been
 * removed. **There is no rich result to earn here and this markup is not
 * emitted in the hope of one.**
 *
 * It is emitted because `FAQPage` is still a valid schema.org type, Google still
 * parses it to understand a page, and unsupported markup neither errors nor
 * costs anything. The campaign FAQ is a real, creator-authored surface (§4.4's
 * FAQ tab, `GET /v1/projects/{id}/faqs`), and describing it accurately is worth
 * doing for the consumers that are not Google.
 *
 * <h2>Only what the page shows</h2>
 *
 * The one guideline that survived every change is that the questions and answers
 * in the markup must be the ones visible on the page, and that `FAQPage` must
 * not be used for promotional copy. This function therefore takes the entries a
 * caller has rendered and nothing else — it does not fetch, it does not compose,
 * and it has no idea what a campaign's FAQ endpoint holds. A page that renders
 * no FAQ passes no entries and gets no node.
 *
 * NOTHING MOUNTS THIS YET, and that is stated rather than hidden. The FAQ tab
 * belongs to the server-rendered campaign page, and the FAQ editor that fills it
 * is CD-15; `components/campaign-editor/StoryPanel.tsx` says the same. It lives
 * here so that the page and this graph land together rather than the page
 * landing first and the markup being remembered later — the same reason
 * `projectPageRobots` was written before anything rendered it.
 */

/** One published pair, as the page shows it. */
export interface FaqEntry {
  readonly question: string;
  readonly answer: string;
}

function collapsed(text: string): string {
  return text.replace(/\s+/gu, ' ').trim();
}

/**
 * The FAQ node for a page, or `null` when there is no FAQ on it.
 *
 * A PAIR WITH A MISSING HALF IS DROPPED, not emitted with an empty string. An
 * unanswered `Question` is a claim that the page answers something it does not,
 * and a `Question` with no `name` is not a question at all.
 */
export function faqPageNode(entries: readonly FaqEntry[], pageUrl: string): JsonLdNode | null {
  const answered = entries
    .map((entry) => ({ question: collapsed(entry.question), answer: collapsed(entry.answer) }))
    .filter((entry) => entry.question !== '' && entry.answer !== '');

  if (answered.length === 0) return null;

  return {
    '@type': 'FAQPage',
    '@id': `${pageUrl}#faq`,
    mainEntity: answered.map((entry) => ({
      '@type': 'Question',
      name: entry.question,
      acceptedAnswer: { '@type': 'Answer', text: entry.answer },
    })),
  };
}
