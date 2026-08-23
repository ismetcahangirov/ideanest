import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { FollowingPanel } from '../../../components/account/FollowingPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Following',
  description: 'The creators whose launches you are told about.',
});

/**
 * `/account/following` — §4.9 C-10, issue #288.
 *
 * The other half of the same endpoint pair as `/account/saved`, and a separate screen rather
 * than a tab beside it: a saved campaign and a followed creator are different objects with
 * different actions on them, and a tab strip would suggest they are two views of one list.
 */
export default function FollowingPage() {
  return (
    <>
      <AccountPageHeader title="Following">
        Creators you follow. Each one sends you a message when they launch something new.
      </AccountPageHeader>

      <div className="mt-8">
        <FollowingPanel />
      </div>
    </>
  );
}
