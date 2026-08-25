import type { ReactNode } from 'react';
import { AccountArea } from '../../../components/account/AccountArea';

/**
 * The pledge screens take the account area's frame — §4.8, issue #290.
 *
 * `/pledges/{id}/address` is reached from a survey (#289) and from the deliveries list, and
 * both of those are inside this frame. A screen that dropped the navigation on the way would
 * leave somebody who came to fix an address with no way back to the parcel that prompted it.
 *
 * No entry in `lib/account/navigation.ts` matches these paths, so nothing is marked current —
 * which is correct. A pledge's address is a screen somebody is sent to, not a destination they
 * choose from a list.
 */
export default function PledgesLayout({ children }: { children: ReactNode }) {
  return <AccountArea>{children}</AccountArea>;
}
