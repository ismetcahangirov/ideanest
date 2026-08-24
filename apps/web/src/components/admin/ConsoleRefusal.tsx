'use client';

import { InlineAlert } from '@ideanest/ui';
import type { ConsoleStatus } from '../../lib/admin/refusals';

export interface ConsoleRefusalProps {
  readonly status: ConsoleStatus;
  /** What the reader was trying to read, in the screen's own words: "the ledger". */
  readonly subject: string;
}

/**
 * The two refusals every console screen can meet before it has anything to draw.
 *
 * <p><strong>`variant="info"` and not `danger`, for both.</strong> Neither is an error in the
 * sense a red panel means: a session that expired is ordinary, and an account that is not on
 * the moderator list has not done anything wrong by opening a URL somebody sent it. Red here
 * would be the interface shouting at somebody for a thing it is itself responsible for.
 *
 * <p>Returns null for the other three statuses, so a caller can render it unconditionally and
 * keep its own branch for the states that have content.
 */
export function ConsoleRefusal({ status, subject }: ConsoleRefusalProps) {
  if (status === 'signed-out') {
    return (
      <InlineAlert variant="info" title="You are signed out">
        This browser no longer has a session. Sign in again to read {subject}.
      </InlineAlert>
    );
  }

  if (status === 'forbidden') {
    return (
      <InlineAlert variant="info" title="Not a moderator">
        The console is read by platform staff, and your account is not on the configured
        moderator list. There is no role model in the access token yet, so that list is the
        whole of the check.
      </InlineAlert>
    );
  }

  return null;
}
