import type { Metadata } from 'next';
import { CheckoutView } from '../../../../components/checkout/CheckoutView';

/**
 * `/projects/{id}/back` — the pledge flow, docs/architecture.md §4.5.
 *
 * <h2>Why this segment</h2>
 *
 * **`back`, because that is what the person is doing.** The product's verb for
 * this is "Back this project" — it is the label on the call to action in
 * docs/motion-system.md §4.3 — and a route reads best when it names the reader's
 * intent rather than the system's process. `checkout` is our word for the
 * machinery and it borrows a shopping cart's mental model, which is wrong here in
 * a way that matters: nothing is bought, nothing is charged today, and the thing
 * at the end is a commitment that may never be collected (§9.2).
 *
 * It sits beside `edit/` and `prelaunch/` under `projects/[id]`, which keeps the
 * three audiences of one campaign in one place: `edit` is the creator's, and
 * `prelaunch` and `back` are the public's.
 *
 * <h2>One route, three steps</h2>
 *
 * There is deliberately no `back/review` or `back/confirm`. The selection is not
 * linkable state — a link would carry one person's half-made pledge to somebody
 * who cannot pay for it — and a reservation lives for five minutes, so a back
 * button that could re-enter the review step would routinely land on a hold that
 * has expired. `CheckoutView` explains the same decision from the other side.
 *
 * <h2>Not indexed</h2>
 *
 * A checkout is not a landing page. A search result pointing here is a dead end
 * for anybody arriving without a session, and the campaign's own page — which
 * this route is reached from — is the thing worth ranking.
 *
 * The rewards are loaded in the browser, so this is a shell and `CheckoutView` is
 * the client boundary — the same shape `/projects/[id]/edit/rewards` takes, and
 * for the same reason: nothing from `@ideanest/ui` may be imported by a Server
 * Component, because it is a barrel and several of its members reach for
 * `createContext`.
 */
export const metadata: Metadata = {
  title: 'Back this campaign',
  description: 'Choose a reward, add extras, and confirm your pledge.',
  robots: { index: false, follow: true },
};

/**
 * PL-15's private link: `?token=abc&token=def`.
 *
 * Repeatable, because a campaign may have handed out more than one and a backer
 * may hold two. Next hands a repeated parameter over as an array and a single one
 * as a string, so both readings are normalised here rather than at the four
 * places downstream that would otherwise each have to.
 */
function secretTokens(value: string | string[] | undefined): readonly string[] {
  if (value === undefined) return [];
  return (Array.isArray(value) ? value : [value]).filter((token) => token !== '');
}

export default async function BackProjectPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id } = await params;
  const query = await searchParams;

  return (
    <main>
      <CheckoutView projectId={id} secretTokens={secretTokens(query['token'])} />
    </main>
  );
}
