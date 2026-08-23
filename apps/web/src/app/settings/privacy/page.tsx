import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { AccountClosurePanel } from '../../../components/settings/AccountClosurePanel';
import { DataExportPanel } from '../../../components/settings/DataExportPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Data and closure',
  description: 'Download a copy of your IdeaNest data, or close your account.',
});

/**
 * `/settings/privacy` — §4.1 A-10 and A-11, issue #279.
 *
 * <h2>The export is above the closure, and the order is the point</h2>
 *
 * They are the two halves of the same moment: §17.4 anonymises a closed account after thirty
 * days, and the export is the only way to keep anything after that. Putting the closure first
 * would be putting the irreversible action above the one that makes it survivable. Somebody
 * who scrolls to the bottom of this page has read the offer to take their data with them.
 *
 * **The order is not enforcement.** A closure does not require an export first, and it should
 * not: making somebody download a file before they may leave is a dark pattern wearing a
 * safety argument.
 */
export default function PrivacyPage() {
  return (
    <>
      <AccountPageHeader title="Data and closure">
        Everything IdeaNest holds about you, and what happens to it if you leave.
      </AccountPageHeader>

      <div className="mt-8 flex flex-col gap-6">
        <DataExportPanel />
        <AccountClosurePanel />
      </div>
    </>
  );
}
