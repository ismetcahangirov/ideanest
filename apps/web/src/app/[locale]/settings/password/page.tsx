import type { Metadata } from 'next';
import { Link } from '../../../../i18n/navigation';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { PasswordChangePanel } from '../../../../components/settings/PasswordChangePanel';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Password',
  description: 'Change the password on your IdeaNest account.',
});

/**
 * `/settings/password` — §4.1 A-13, issue #277.
 *
 * A shell around a client boundary, like the rest of the account area. The one call on it is a
 * write behind a bearer token, so there is nothing a server render could produce.
 *
 * <h2>It links to the reset rather than hiding it</h2>
 *
 * A-13 requires the current password, and there are two people who cannot supply one: somebody
 * who has forgotten it, and somebody who registered through Google or Apple and has never had
 * one — `AccountCredentialsService` refuses the second with the same `incorrect-password` as
 * the first, because an account with no credential row has nothing to confirm with.
 *
 * `/reset-password` is the documented way through for both. `PasswordResetService` says so in
 * as many words: an account with no password can still reset one, and that is "the documented
 * way back for a person who has lost access to the provider account they signed up with — the
 * alternative is an account that can never be reached again, which is a support ticket with no
 * answer". A page that offered only the form above would leave both of them stuck on a refusal
 * whose real remedy is one link away.
 *
 * The link is here, in the page's own standfirst, rather than inside the panel: it is an
 * alternative to the whole form and not a footnote to one of its fields.
 */
export default function PasswordSettingsPage() {
  return (
    <>
      <AccountPageHeader title="Password">
        Changing it needs the one you have now. If you do not have it — or you have only ever
        signed in with Google or Apple —{' '}
        <Link href="/reset-password" className="text-white underline underline-offset-4">
          reset it by email
        </Link>{' '}
        instead.
      </AccountPageHeader>

      <div className="mt-8">
        <PasswordChangePanel />
      </div>
    </>
  );
}
