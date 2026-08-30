'use client';

import { InlineAlert } from '@ideanest/ui';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import type { ConsoleRefusalsCopy } from '../../lib/i18n/admin/common-copy';
import type { ConsoleStatus } from '../../lib/admin/refusals';

export interface ConsoleRefusalProps {
  readonly status: ConsoleStatus;
  /** What the reader was trying to read, in the screen's own words: "the ledger". */
  readonly subject: string;
  readonly copy: ConsoleRefusalsCopy;
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
 *
 * <h2>This was the last component that could not be translated, and why it can be now</h2>
 *
 * `apps/web/README.md` used to list this file as the exception: the signed-out sentence names
 * the thing a screen was about to show, the noun comes from the screen, and while the screens
 * were English literals there was no translated noun to put in it. Every screen carries a
 * catalogue node since #324, so `subject` arrives already translated and already inflected for
 * its position, and the sentence is a template around it rather than a concatenation.
 */
export function ConsoleRefusal({ status, subject, copy }: ConsoleRefusalProps) {
  if (status === 'signed-out') {
    return (
      <InlineAlert variant="info" title={copy.signedOutTitle}>
        {fillPlaceholders(copy.signedOutBody, { subject })}
      </InlineAlert>
    );
  }

  if (status === 'forbidden') {
    return (
      <InlineAlert variant="info" title={copy.forbiddenTitle}>
        {copy.forbiddenBody}
      </InlineAlert>
    );
  }

  return null;
}
