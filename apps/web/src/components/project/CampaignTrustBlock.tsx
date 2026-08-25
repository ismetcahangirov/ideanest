import { ShieldCheck } from 'lucide-react';
import { formatInstant, SERVER_TIME_ZONE } from '../../lib/projects/deadline';
import { formatMoney } from '../../lib/money';
import type { CampaignPage } from '../../lib/projects/publicPage';
import { ViewerInstant } from './ViewerClock';
import { getTranslations } from 'next-intl/server';

/**
 * §4.4's trust block — issue #281. Fixed copy on every project, plus the all-or-nothing
 * statement with the deadline in the reader's own time zone.
 *
 * <h2>The three sentences are quoted, not paraphrased</h2>
 *
 * §4.4 prints them as a block quotation and calls them "fixed copy on every project". They
 * are reproduced below word for word, and {@link TRUST_COPY} is exported so that a test can
 * assert the page renders exactly those words rather than something a later edit made
 * friendlier.
 *
 * <strong>THE CONSTANT SURVIVED TRANSLATION, AND IS NOW WHAT GUARDS IT.</strong> The
 * paragraph is drawn from `campaign.trust.body` so that a Russian backer reads the promise
 * rather than looking at it, and `TRUST_COPY` remains the English original.
 * `CampaignHeader.test.tsx` asserts the catalogue's English matches this constant exactly,
 * so the sentence can be translated but not quietly reworded — which is the failure §4.4's
 * "word for word" is actually about. Whoever adds the fourth language writes a translation;
 * whoever edits the English has to edit the constant and see this comment.
 *
 * That matters more than it looks. Each sentence is a promise about somebody's money, and
 * the third — "You are only charged if the project reaches its goal by the deadline" — is the
 * platform's entire commercial model stated to the person about to rely on it. A campaign
 * that rendered a softened version of it would be a campaign making a different promise from
 * the one the platform makes, and nobody would notice until a backer quoted it back.
 *
 * <strong>It is shown on every campaign, including the closed ones.</strong> "Every project"
 * is what §4.4 says, and a reader arriving at a campaign that failed a year ago is entitled
 * to read the rule that decided nobody was charged.
 *
 * <h2>The all-or-nothing sentence is a second statement, not a restatement</h2>
 *
 * The fixed copy says what the rule is. §4.4 additionally requires "an explicit
 * all-or-nothing statement with the deadline in the viewer's timezone", which is what the
 * rule means <em>for this campaign</em>: this goal, this instant, and the instant written in
 * the zone the reader lives in rather than the one the server happens to run in. A reader who
 * has to convert a UTC timestamp in their head to work out whether they still have time to
 * pledge has been given the deadline in a form that is technically correct and practically
 * useless.
 *
 * The tense follows the state rather than the clock. A campaign whose deadline has passed
 * gets the past tense, because "you will only be charged if it reaches its goal by 29 August"
 * printed in September is a sentence about a decision that has already been made.
 *
 * <h2>Colour</h2>
 *
 * A neutral panel. <strong>Not lime</strong> — lime is "act now" (docs/ui-kit.md §2.4) and
 * this block is the opposite of urgency: it is the paragraph that tells somebody they are not
 * being charged yet. Not `--success` either, which would read as a claim that this particular
 * campaign is safe. The icon is `--text-secondary` and carries no meaning colour has to
 * decode (§9.2); the words carry all of it.
 *
 * <h2>Motion</h2>
 *
 * None. docs/motion-system.md §5's rule — motion decreases as money gets closer — puts this
 * block at the far end of it: a paragraph about whether somebody will be charged, animating
 * into view, reads as hesitation about the answer.
 */

/**
 * §4.4's fixed copy, verbatim.
 *
 * Three sentences in one string rather than three, so a reordering or a dropped sentence is a
 * visible diff rather than a rearranged array. The test asserts this constant and the render
 * agree.
 */
export const TRUST_COPY =
  'The platform connects creators with backers. Rewards are not guaranteed, but creators ' +
  'must keep backers informed. You are only charged if the project reaches its goal by the ' +
  'deadline.';

/**
 * The states in which the deadline is still ahead of the reader.
 *
 * Read from the state rather than by comparing the deadline with the clock, for the reason
 * `CampaignOutcomeNotice` gives: recomputing "is this campaign still open" from a timestamp
 * would be a second implementation of §5.1 in the browser, and the day it disagreed with the
 * service it would disagree on somebody's campaign page.
 */
const OPEN_STATES: readonly CampaignPage['state'][] = ['PRELAUNCH', 'LIVE'];

export interface CampaignTrustBlockProps {
  readonly campaign: CampaignPage;
}

export async function CampaignTrustBlock({ campaign }: CampaignTrustBlockProps) {
  const t = await getTranslations('campaign.trust');

  const open = OPEN_STATES.includes(campaign.state);

  /*
   * Formatted here, in UTC, and handed to the client island as its starting value. The island
   * substitutes the reader's zone after hydration; `ViewerClock`'s own comment explains why
   * the server cannot know it and why the first render must match the HTML anyway. A reader
   * with no JavaScript keeps this string, which is a real instant with its zone named.
   */
  const serverDeadline =
    campaign.deadline === null ? null : formatInstant(campaign.deadline, SERVER_TIME_ZONE);

  return (
    <section
      aria-labelledby="campaign-trust"
      className="mt-8 flex flex-col gap-3 rounded-lg border border-white/8 bg-surface-2 p-5 sm:p-6"
    >
      <h2 id="campaign-trust" className="flex items-center gap-2 text-base font-medium text-white">
        <ShieldCheck aria-hidden="true" className="size-5 text-white/64" />
        {t('heading')}
      </h2>

      <p className="max-w-[68ch] text-sm leading-relaxed text-reading">{t('body')}</p>

      {campaign.deadline !== null && serverDeadline !== null && (
        <p className="max-w-[68ch] text-sm leading-relaxed text-white/64">
          {open ? (
            <>
              All or nothing:{' '}
              {campaign.goal === null ? (
                <>this campaign is only funded if it reaches its goal by </>
              ) : (
                <>
                  this campaign is only funded if it raises{' '}
                  <strong className="font-medium text-white">{formatMoney(campaign.goal)}</strong> by{' '}
                </>
              )}
              <strong className="font-medium text-white">
                <ViewerInstant instant={campaign.deadline} serverText={serverDeadline} />
              </strong>
              . If it does not, nobody is charged anything.
            </>
          ) : (
            <>
              All or nothing: this campaign closed on{' '}
              <strong className="font-medium text-white">
                <ViewerInstant instant={campaign.deadline} serverText={serverDeadline} />
              </strong>
              . Backers were charged only because it reached its goal by then; a campaign that
              does not reach its goal collects nothing.
            </>
          )}
        </p>
      )}

      {/*
        NO DEADLINE, NO SENTENCE. A campaign in `PRELAUNCH` has not been submitted yet and has
        neither a goal nor a closing date (§5.3), so there is nothing to be explicit about. The
        fixed copy above still states the rule, which is the part §4.4 requires on every
        project; inventing a date to have something to name would be the one failure this whole
        block exists to prevent.
      */}
    </section>
  );
}
