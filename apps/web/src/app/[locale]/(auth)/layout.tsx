import type { ReactNode } from 'react';
import { MinimalShell } from '../../../components/shell/MinimalShell';

/**
 * The frame the authentication screens render inside — §4.1, issues #267 to #270.
 *
 * <h2>Why this is not `(site)`'s shell</h2>
 *
 * A sign-in page is a screen with one job, and the site header's job is to offer eleven other
 * ones. Putting the full navigation on it would mean everybody who arrived to sign in is shown
 * Discover, Categories, a search field, and — worst of the four — a Register button beside the
 * form they are already trying to use. docs/ui-kit.md §8.5 makes the same argument about the
 * checkout: the screen where somebody is doing one thing is not the screen to offer
 * alternatives on.
 *
 * So the chrome here is a wordmark that goes home and nothing else. It is still a way out,
 * which is the one thing chrome has to be. `MinimalShell` is that frame, shared with the
 * failure states, which want it for a related reason and for a measured one besides.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives authentication a budget of "none — 150ms colour on controls",
 * and states the reason in a sentence worth keeping: signing in is work with a wrong-password
 * branch at the end of it, and an animated error is one that arrives after it was needed.
 * There is no `FadeUp` on these pages and no collapsing header, because §4.7's collapse belongs
 * to a bar that is not here.
 *
 * <h2>The column width</h2>
 *
 * §6.2's layout scale is written for dashboards. What a form wants is a width at which a
 * label, a field and an error read as one column — about 26rem — which is why every one of
 * these pages is the same width whatever it contains.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <MinimalShell
      centred
      footer={
        <p>
          Reward-based crowdfunding. Nobody is charged unless a campaign reaches its goal by its
          deadline.
        </p>
      }
    >
      <div className="w-full max-w-[26rem]">{children}</div>
    </MinimalShell>
  );
}
