import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { SessionsPanel } from '../../../../components/sessions/SessionsPanel';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Active sessions',
  description: 'Review the devices signed in to your IdeaNest account and sign out of any of them.',
});

/**
 * The list is per-account and authenticated with a bearer token held in memory,
 * so there is nothing here a server render could produce — the page is a shell
 * and `SessionsPanel` is the client boundary.
 *
 * **It lost its own `<main>` and its own page padding in #275.** Both belong to the account
 * area's layout now: `SiteShell` owns the single `<main>` on the page, and a second one would
 * leave assistive technology with two answers to "jump to main".
 */
export default function SessionsPage() {
  return (
    <>
      <AccountPageHeader title="Devices">
        Every device currently signed in to your account. If you do not recognise one, sign it
        out — it stops being able to refresh within fifteen minutes.
      </AccountPageHeader>

      <div className="mt-8">
        <SessionsPanel />
      </div>
    </>
  );
}
