import type { ReactNode } from 'react';
import { SiteShell } from '../shell/SiteShell';
import { AccountNav } from './AccountNav';

/**
 * The frame every account screen renders inside — §4.2 and §4.13 WS-01, issue #275.
 *
 * <h2>It takes the site shell, and the authentication screens do not</h2>
 *
 * `app/(auth)` uses `MinimalShell` because a sign-in page is a screen with one job and the
 * header's job is to offer eleven others. **The account area is the opposite case.** Somebody
 * managing their notifications is already signed in, is not mid-transaction, and the most
 * likely next thing they want is a campaign — so taking the navigation away would be taking
 * away the way out. `apps/web/README.md`'s route table records the move.
 *
 * <h2>The navigation is beside the content, not above it</h2>
 *
 * docs/ui-kit.md §6.3 puts a rail on the left of a working surface, and this is one: eight
 * destinations somebody moves between, several times, in one sitting. Above the content it
 * would push the page down on every screen and would compete with the site header a few
 * pixels above it.
 *
 * <h2>No second `<main>`</h2>
 *
 * `SiteShell` owns the only one on the page, and the screens under this used to declare their
 * own — `/settings/sessions` and `/settings/notifications` both did, because they had no
 * shell to be inside. Two `<main>` elements is not a duplicated landmark so much as an
 * ambiguous one: assistive technology offers "jump to main" and there is now more than one
 * answer. Both pages were changed in the same pull request that gave them this frame.
 */
export interface AccountAreaProps {
  readonly children: ReactNode;
}

export function AccountArea({ children }: AccountAreaProps) {
  return (
    <SiteShell>
      <div className="mx-auto w-full max-w-[1120px] px-5 py-10 sm:px-6 sm:py-14">
        <div className="flex flex-col gap-10 lg:flex-row lg:gap-14">
          <div className="lg:w-[15rem] lg:shrink-0">
            <AccountNav />
          </div>
          {/*
            `min-w-0` so a long tracking number or an unbroken address line scrolls inside its
            own container rather than widening the flex row and pushing the navigation off the
            side of the page.
          */}
          <div className="min-w-0 flex-1">{children}</div>
        </div>
      </div>
    </SiteShell>
  );
}
