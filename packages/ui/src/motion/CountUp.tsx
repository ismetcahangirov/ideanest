import { animate, useInView, useReducedMotion } from 'motion/react';
import { useEffect, useRef, type ComponentPropsWithoutRef } from 'react';
import { duration as tokenDuration } from '@ideanest/design-tokens';

/**
 * Counts from zero to `to` when scrolled into view.
 *
 * Deliberately 800ms rather than the two seconds marketing sites favour.
 * These are real figures — a pledge total that visibly crawls upward reads
 * as "still loading", which is exactly the wrong signal on a funding page.
 * See docs/motion-system.md §4.8.
 *
 * Accessibility: `aria-label` carries the final value, and the animated node
 * is `aria-hidden`, so assistive tech never reads a ticking number.
 */
export interface CountUpProps extends Omit<ComponentPropsWithoutRef<'span'>, 'children'> {
  to: number;
  /** Milliseconds. */
  durationMs?: number;
  /** Rendered before the number, e.g. a currency symbol. */
  prefix?: string;
  /** Rendered after the number, e.g. `%`. */
  suffix?: string;
  /** Decimal places. */
  decimals?: number;
  /** Locale for digit grouping. */
  locale?: string;
}

export function CountUp({
  to,
  durationMs = tokenDuration.countUp,
  prefix = '',
  suffix = '',
  decimals = 0,
  locale = 'en-US',
  className,
  ...props
}: CountUpProps) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: '-15% 0px' });
  const reduced = useReducedMotion();

  const format = (v: number) =>
    prefix +
    v.toLocaleString(locale, {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }) +
    suffix;

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    if (reduced || !inView) {
      // Reduced motion still needs the final value painted, not a stuck zero.
      if (reduced) node.textContent = format(to);
      return;
    }

    const controls = animate(0, to, {
      duration: durationMs / 1000,
      ease: [0, 0, 0.2, 1],
      onUpdate: (v) => {
        node.textContent = format(v);
      },
    });

    return () => controls.stop();
    // `format` is derived from the formatting props listed here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inView, reduced, to, durationMs, prefix, suffix, decimals, locale]);

  return (
    <span className={className} aria-label={format(to)} {...props}>
      <span ref={ref} aria-hidden="true" className="tabular-nums">
        {format(0)}
      </span>
    </span>
  );
}
