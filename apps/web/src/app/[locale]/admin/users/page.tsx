import type { Metadata } from 'next';
import { UserDirectory } from '../../../../components/admin/UserDirectory';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-04 (#104): the account directory, and it is staff-only.
 *
 * `privatePageMetadata` for the reason `/admin/moderation` gives, and one of its
 * own: this screen renders other people's email addresses, so `noindex, nofollow`
 * and no social card is the least of what it needs. THE ROUTE IS NOT A GATE —
 * there is no role model in the schema or the access token until #295, so the
 * service refuses a caller who is not on the configured moderator list and the
 * panel renders that refusal. Anything gating here would be a second, weaker copy
 * of a check the service already makes correctly, and the two would eventually
 * disagree.
 *
 * <p>Inside the console shell since #294, and no longer carrying a `<main>` of its
 * own — `/admin/moderation` states the argument.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Accounts',
  description: 'Search the platform accounts, and stop the ones that have to be stopped.',
});

export default function AdminUsersPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">Accounts</h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Every account on the platform, searched by address, display name or profile slug.
        Suspending one signs it out everywhere and refuses it at sign-in; it changes nothing about
        the campaigns it created or the pledges it made, and those are separate decisions with
        separate consequences.
      </p>

      <div className="mt-8">
        <UserDirectory />
      </div>
    </div>
  );
}
