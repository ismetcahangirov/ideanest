'use client';

import Link from 'next/link';
import { Pill, Tag } from '@ideanest/ui';
import type { InboxNotification } from '../../lib/notifications/api';
import { categoryLabel, describeNotification } from '../../lib/notifications/describe';
import { formatExactTime, formatRelativeTime } from '../../lib/time';

export interface NotificationRowProps {
  readonly notification: InboxNotification;
  /** Pinned per load, so every row's "ago" is measured from one instant. */
  readonly now: Date;
  readonly busy: boolean;
  readonly onOpen: (notification: InboxNotification) => void;
}

/**
 * One row of the inbox.
 *
 * <h2>Unread is a word as well as a dot</h2>
 *
 * CLAUDE.md §2 forbids colour carrying meaning on its own, and "unread" is exactly the kind
 * of state a coloured dot is usually asked to carry alone. So the dot is `aria-hidden` and
 * the row states it in text — visible text, not only a screen-reader string, because the
 * people a colour-only cue fails include sighted readers.
 *
 * <h2>Two controls, and why the link is one of them</h2>
 *
 * Opening a notification is what marks it read, which is what a reader expects and what
 * stops the badge counting things somebody has already dealt with. So the headline is a
 * link that navigates *and* reports the read, and there is a separate button for the rows
 * with nowhere to go and for a reader who wants to clear one without opening it.
 *
 * A row whose document names no campaign has no link at all — `describe.ts` argues why an
 * inbox row that goes nowhere useful is better as plain text than as a live-looking link.
 *
 * MOTION IS NEAR ZERO, following `SessionsPanel`: this is a screen where somebody is
 * working through a list, not exploring, so it gets colour on hover and nothing else. §8
 * rules out staggering a list regardless.
 */
export function NotificationRow({ notification, now, busy, onOpen }: NotificationRowProps) {
  const view = describeNotification(notification);
  const unread = notification.readAt === undefined || notification.readAt === null;

  return (
    <li className="flex items-start gap-3 px-5 py-4">
      {/*
        White rather than lime. docs/ui-kit.md §8.1 reserves lime for "act now", and it
        makes the same call about a filter chip and a hovered row: something new is not
        something urgent. A lime dot on every unread row would leave nothing louder for
        the campaign that closes in an hour.
      */}
      <span
        aria-hidden="true"
        className={`mt-2 size-2 shrink-0 rounded-full ${unread ? 'bg-white' : 'bg-white/16'}`}
      />

      <div className="min-w-0 flex-1">
        <p className="text-sm text-white">
          {view.href === null ? (
            view.headline
          ) : (
            <Link
              href={view.href}
              onClick={() => onOpen(notification)}
              className="rounded-sm underline-offset-4 transition-colors duration-150 hover:underline"
            >
              {view.headline}
            </Link>
          )}
        </p>

        <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-white/56">
          <Tag>{categoryLabel(notification.category)}</Tag>
          <time dateTime={notification.occurredAt} title={formatExactTime(notification.occurredAt)}>
            {formatRelativeTime(notification.occurredAt, now)}
          </time>
          {unread && <span className="text-white/72">Unread</span>}
        </p>
      </div>

      {unread && (
        <Pill
          variant="ghost"
          size="sm"
          disabled={busy}
          onClick={() => onOpen(notification)}
          className="shrink-0"
        >
          {busy ? 'Marking' : 'Mark as read'}
        </Pill>
      )}
    </li>
  );
}
