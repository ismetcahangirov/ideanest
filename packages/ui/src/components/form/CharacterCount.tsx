import { useEffect, useState, type ComponentPropsWithoutRef } from 'react';
import { cn } from '../../lib/cn';

/**
 * How much of a length limit is left. See docs/ui-kit.md §7.13.
 *
 * COLOUR IS NOT THE MESSAGE. Passing the limit changes the wording — "3
 * characters too many" — and only then the colour (§9.2). A counter that merely
 * turns red has told a colour-blind creator nothing, and a screen reader
 * nothing at all.
 *
 * THE VISIBLE COUNT IS `aria-hidden`, AND THE ANNOUNCEMENT IS SEPARATE. Two
 * different things are needed from one number: a sighted creator wants it
 * present at all times, and a screen-reader user wants to hear it only when it
 * starts to matter. Announcing every keystroke would talk over the typing echo
 * — the field becomes unusable long before the limit is reached — so the live
 * region stays empty until the remainder is inside `announceWithin`, and then
 * settles for `announceDelayMs` before it says anything. It is the pattern the
 * GOV.UK character count arrived at after user testing, for the same reason.
 *
 * `role="status"` is polite by definition: it waits for a pause rather than
 * interrupting. An `alert` here would interrupt, which is precisely the
 * behaviour being avoided.
 *
 * Counting is the caller's job, through `count`. Characters are not code units:
 * `'🙂'.length` is 2, and a counter that says 61 while the database is happy
 * with 60 is a counter that lies. The web application counts code points, which
 * is how Postgres counts `varchar(60)`.
 */
export interface CharacterCountProps extends Omit<ComponentPropsWithoutRef<'p'>, 'children'> {
  /** Characters used, already counted the way the storage counts them. */
  count: number;
  limit: number;
  /** Start announcing once this many characters or fewer remain. */
  announceWithin?: number;
  /** How long the count must be still before it is announced. */
  announceDelayMs?: number;
}

export function CharacterCount({
  count,
  limit,
  announceWithin = 20,
  announceDelayMs = 1000,
  className,
  ...props
}: CharacterCountProps) {
  const remaining = limit - count;
  const over = remaining < 0;

  const visible = over
    ? `${-remaining} ${plural(-remaining)} too many`
    : `${remaining} ${plural(remaining)} remaining`;

  const [announced, setAnnounced] = useState('');

  useEffect(() => {
    if (remaining > announceWithin) {
      // Clearing rather than leaving the last message behind: a stale "2
      // characters remaining" is read again by anything that re-announces the
      // region, and it is no longer true.
      setAnnounced('');
      return;
    }

    const timer = setTimeout(() => setAnnounced(visible), announceDelayMs);
    return () => clearTimeout(timer);
  }, [visible, remaining, announceWithin, announceDelayMs]);

  return (
    <p
      {...props}
      className={cn(
        'text-[13px] tabular-nums transition-colors duration-150 ease-in-out',
        over ? 'text-danger' : 'text-white/40',
        className,
      )}
    >
      <span aria-hidden="true">{visible}</span>

      {/*
        Rendered on every pass so the region exists in the accessibility tree
        before anything is put in it. A live region created and filled in the
        same commit is not reliably announced.
      */}
      <span role="status" aria-live="polite" className="sr-only">
        {announced}
      </span>
    </p>
  );
}

function plural(value: number): string {
  return value === 1 ? 'character' : 'characters';
}
