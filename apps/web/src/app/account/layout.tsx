import type { ReactNode } from 'react';
import { AccountArea } from '../../components/account/AccountArea';

/**
 * The backer half of the account area — §4.8 and §4.9, issues #288, #289 and #290.
 *
 * The same frame `app/settings/layout.tsx` renders, for the reason stated there: one
 * navigation across both prefixes, so somebody who came to answer a survey can reach their
 * notification settings without going back to the footer.
 */
export default function AccountLayout({ children }: { children: ReactNode }) {
  return <AccountArea>{children}</AccountArea>;
}
