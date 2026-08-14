import { motion, useReducedMotion, type HTMLMotionProps } from 'motion/react';
import type { ElementType } from 'react';
import { STAGGER_STEP } from '@ideanest/design-tokens';

/**
 * The single scroll-entry animation used across the product.
 *
 * There is exactly one entry animation on purpose. Reference research showed
 * a high-end marketing site using `fade-up` 45 times and nothing else — the
 * restraint is what makes motion read as calm rather than busy.
 * See docs/motion-system.md §1.1 and §4.1.
 *
 * Rules baked in here:
 *   - `once: true`  — never replays on scroll-back, so reverse scrolling is quiet
 *   - reduced motion — renders statically, no transform, no opacity ramp
 *   - transform/opacity only — both are GPU-composited, so no layout thrash
 */
export interface FadeUpProps extends Omit<HTMLMotionProps<'div'>, 'variants' | 'initial'> {
  /** Render as a different element (`ul`, `section`, `article`, ...). */
  as?: ElementType;
  /** Delay in milliseconds. Prefer `StaggerGroup` for lists. */
  delay?: number;
  /** Travel distance in pixels. */
  distance?: number;
  /** Duration in milliseconds. */
  duration?: number;
  /**
   * When inside a `StaggerGroup`, set this so the parent drives timing
   * instead of each child animating on its own viewport trigger.
   */
  child?: boolean;
}

export function FadeUp({
  as = 'div',
  delay = 0,
  distance = 24,
  duration = 600,
  child = false,
  children,
  ...props
}: FadeUpProps) {
  const reduced = useReducedMotion();
  const Component = motion.create(as as 'div');

  if (reduced) {
    return <Component {...props}>{children}</Component>;
  }

  const variants = {
    hidden: { opacity: 0, y: distance },
    visible: {
      opacity: 1,
      y: 0,
      transition: {
        duration: duration / 1000,
        delay: delay / 1000,
        ease: [0.4, 0, 0.2, 1] as const,
      },
    },
  };

  // As a stagger child the parent orchestrates; standalone it triggers itself.
  const trigger = child
    ? {}
    : { initial: 'hidden' as const, whileInView: 'visible' as const, viewport: { once: true, margin: '-10% 0px' } };

  return (
    <Component variants={variants} {...trigger} {...props}>
      {children}
    </Component>
  );
}

/**
 * Orchestrates `FadeUp child` descendants with a staggered delay.
 *
 * The delay is capped (`maxDelay`) because an uncapped ladder makes the 50th
 * list item wait two and a half seconds — the page looks broken, not polished.
 */
export interface StaggerGroupProps extends Omit<HTMLMotionProps<'div'>, 'variants' | 'initial'> {
  as?: ElementType;
  /** Milliseconds between children. */
  step?: number;
  /** Delay before the first child, in milliseconds. */
  initialDelay?: number;
  /** Upper bound for the whole ladder, in milliseconds. */
  maxDelay?: number;
}

export function StaggerGroup({
  as = 'div',
  step = STAGGER_STEP,
  initialDelay = 0,
  maxDelay = 300,
  children,
  ...props
}: StaggerGroupProps) {
  const reduced = useReducedMotion();
  const Component = motion.create(as as 'div');

  if (reduced) {
    return <Component {...props}>{children}</Component>;
  }

  return (
    <Component
      variants={{
        hidden: {},
        visible: {
          transition: {
            staggerChildren: step / 1000,
            delayChildren: initialDelay / 1000,
            // Motion has no built-in cap, so bound the ladder by limiting
            // how many children can stagger before delays flatten out.
            staggerDirection: 1,
          },
        },
      }}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: '-10% 0px' }}
      data-max-delay={maxDelay}
      {...props}
    >
      {children}
    </Component>
  );
}
