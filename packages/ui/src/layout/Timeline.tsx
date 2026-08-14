import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import { cn } from '../lib/cn';

/**
 * Horizontal campaign timeline. See docs/ui-kit.md §7.8.
 *
 * The lime surface is correct here: a live campaign is time-bound, and the
 * whole point of the strip is "the clock is running".
 *
 * Positions are percentages between `start` and `end`, so callers pass real
 * dates and the component does the projection.
 */
export interface TimelineMarker {
  id: string;
  /** Position in time. Clamped to the track. */
  at: Date;
  label: string;
  /** Rendered on the track — avatar, icon, or short text. */
  content?: ReactNode;
  /** Draws the marker as the "now" indicator instead of a chip. */
  isNow?: boolean;
}

export interface TimelineProps extends Omit<ComponentPropsWithoutRef<'div'>, 'children'> {
  start: Date;
  end: Date;
  markers?: TimelineMarker[];
  /** Current time. Defaults to `start` so server rendering stays deterministic. */
  now?: Date;
  /** Accessible description of what the track represents. */
  label?: string;
}

function ratio(at: Date, start: Date, end: Date): number {
  const span = end.getTime() - start.getTime();
  if (span <= 0) return 0;
  return Math.max(0, Math.min(1, (at.getTime() - start.getTime()) / span));
}

export function Timeline({
  start,
  end,
  markers = [],
  now,
  label = 'Campaign timeline',
  className,
  ...props
}: TimelineProps) {
  const nowRatio = now ? ratio(now, start, end) : null;

  return (
    <div
      role="group"
      aria-label={label}
      className={cn(
        'relative flex h-11 items-center rounded-full bg-lime-500 px-4',
        className,
      )}
      data-on-lime=""
      {...props}
    >
      {/* Elapsed portion, darkened so progress is legible without a second hue. */}
      {nowRatio !== null && (
        <div
          aria-hidden="true"
          className="absolute inset-y-0 left-0 rounded-l-full bg-on-lime/10"
          style={{ width: `${nowRatio * 100}%` }}
        />
      )}

      {markers.map((m) => {
        const left = ratio(m.at, start, end) * 100;

        if (m.isNow) {
          return (
            <div
              key={m.id}
              aria-label={m.label}
              className="absolute inset-y-1 w-0.5 -translate-x-1/2 rounded-full bg-on-lime"
              style={{ left: `${left}%` }}
            />
          );
        }

        return (
          <div
            key={m.id}
            title={m.label}
            className="absolute -translate-x-1/2"
            style={{ left: `${left}%` }}
          >
            {m.content ?? (
              <span className="rounded-full bg-on-lime/10 px-2.5 py-1 text-xs font-medium text-on-lime">
                {m.label}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
