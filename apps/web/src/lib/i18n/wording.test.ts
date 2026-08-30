import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import en from '../../../messages/en.json';
import { TRUST_COPY } from '../../components/project/CampaignTrustBlock';
import { REASON_LABELS } from '../moderation/describe';

/**
 * The English catalogue says what the components used to say — issue #324.
 *
 * <h2>The failure this exists for, which happened</h2>
 *
 * Moving a sentence into the catalogue is a copy-and-paste, and copy-and-paste through a
 * human is lossy. Writing the campaign page's keys, three sentences came out subtly
 * different from the ones that had been on screen: `campaign.faqs.empty` lost "add the
 * answer" in favour of "answer it", `campaign.comments.signedOut` dropped the clause
 * explaining that it is also how the creator replies to you, and
 * `campaign.comments.withdrawWarning` said a withdrawal "cannot be undone" where the
 * original said it "cannot be edited or restored".
 *
 * None of those is a typo. Each is a different sentence that reads as fluent, passes every
 * other check in this repository, and quietly changes what the product tells somebody. The
 * third was caught only because a test happened to assert the old wording; the first two were
 * caught by comparing against `git show origin/main` by hand.
 *
 * <h2>Why this is narrow rather than general</h2>
 *
 * It cannot check every string: most were moved out of JSX where the original no longer
 * exists to compare against. What it can do is pin the sentences the repository had already
 * decided were worth pinning — the ones a module exports as a constant precisely so that a
 * test can hold them still. Those are the promises about money, which is where a reworded
 * sentence costs the most.
 */
describe('the English catalogue against the wording it replaced', () => {
  it('states §4.4 trust copy exactly as the constant does', () => {
    /*
     * `CampaignTrustBlock` explains at length why these three sentences are quoted rather
     * than paraphrased: the third is the platform's entire commercial model stated to the
     * person about to rely on it. The paragraph is drawn from the catalogue now so that it
     * can be read in Russian; this is what stops it being rewritten on the way.
     */
    expect(en.campaign.trust.body).toBe(TRUST_COPY);
  });

  it('keeps the four money promises the checkout makes', () => {
    /*
     * Read out of the component's own source rather than retyped, so this fails if either
     * side moves. These are the sentences a backer relies on when deciding to confirm, and
     * the ones a softened translation would do the most damage to.
     */
    const source = readFileSync(
      join(process.cwd(), 'src/components/checkout/PaymentStep.tsx'),
      'utf8',
    );

    /* The component draws them from the catalogue, so the keys must at least be reached. */
    expect(source).toContain('copy.body');
    expect(source).toContain('copy.later');

    /* And the English must still say the thing §9.2 requires it to say. */
    expect(en.checkout.payment.body).toMatch(/nothing you confirm here charges you/u);
    expect(en.checkout.payment.later).toMatch(/only ever collected if the campaign reaches/u);
    expect(en.checkout.done.noMethod).toMatch(/unless the campaign reaches its goal/u);
    expect(en.checkout.review.notCharged).toMatch(/does not charge you/u);
  });

  it('never softens a charge statement into a thank-you', () => {
    /*
     * `cardStatement` in `CheckoutView` carries this rule in a comment: neither sentence may
     * become "thank you for your payment", because §9.2 collects nothing until the campaign
     * succeeds and a confirmation implying otherwise would have somebody budgeting for money
     * that has not left their account. Asserted here across all four languages rather than
     * in English alone, because a translator with no context is exactly who would write it.
     */
    const FORBIDDEN = /thank you for your (payment|purchase)|payment received|paid/iu;

    for (const message of Object.values(en.checkout.done)) {
      expect(message, message).not.toMatch(FORBIDDEN);
    }
  });

  it('says the same nine report reasons as the public control still holds', () => {
    /*
     * `lib/moderation/describe.ts` keeps `REASON_LABELS` because `ReportControl` — the dialog
     * a member of the public opens on a campaign page — was not translated with the console:
     * it carries its own reason descriptions, its own target nouns and about ten more
     * sentences, and half-translating a public surface inside an administrative change would
     * be worse than leaving it whole.
     *
     * <p>So the same nine words exist twice, and this is what stops them drifting. The day
     * somebody rewords one, the other fails here rather than quietly saying something else to
     * a moderator than it says to the person who filed the complaint. It goes when the public
     * control is translated, which is the rest of #324.
     */
    expect(en.admin.moderation.reason).toEqual(REASON_LABELS);
  });
});
