import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { SavedProjectsPanel } from '../../../components/account/SavedProjectsPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Saved projects',
  description: 'The campaigns you saved to come back to.',
});

/**
 * `/account/saved` — §4.9 C-10, issue #288.
 *
 * A shell around a client boundary. The list is one account's, behind a bearer token, so there
 * is nothing a server render could produce — the same arrangement every other account screen
 * uses.
 */
export default function SavedProjectsPage() {
  return (
    <>
      <AccountPageHeader title="Saved projects">
        Campaigns you saved to come back to. Saving is private — a creator is not told.
      </AccountPageHeader>

      <div className="mt-8">
        <SavedProjectsPanel />
      </div>
    </>
  );
}
