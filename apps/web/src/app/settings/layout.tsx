import type { ReactNode } from 'react';
import { AccountArea } from '../../components/account/AccountArea';

/**
 * The settings half of the account area — §4.2, issue #275.
 *
 * Two layouts rather than one, because there are two URL prefixes and Next files a layout
 * under a path. `AccountArea` is the shared frame, so the navigation, the shell and the
 * column widths are stated once whichever half a reader is on.
 *
 * **The prefixes are a fact about the existing URLs, not a design.**
 * `/settings/notifications` is the address in every notification email the platform has sent;
 * moving it under `/account` to buy a tidier tree would break links this repository does not
 * own. `lib/account/navigation.ts` records the same reasoning beside the data.
 */
export default function SettingsLayout({ children }: { children: ReactNode }) {
  return <AccountArea>{children}</AccountArea>;
}
