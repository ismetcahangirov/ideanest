import { cva, type VariantProps } from 'class-variance-authority';
import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import { cn } from '../../lib/cn';

/**
 * The system's primary action element. See docs/ui-kit.md §7.2.
 *
 *   primary (white)  — main action, sits above the system
 *   accent  (lime)   — URGENT action; at most one per screen, or urgency
 *                      stops meaning anything
 *   ghost / outline  — secondary
 */
const pill = cva(
  [
    'inline-flex items-center justify-center gap-2 whitespace-nowrap',
    'rounded-full font-medium tracking-[-0.01em]',
    'transition-[background-color,transform,border-color]',
    'duration-150 ease-in-out',
    'hover:-translate-y-px active:translate-y-0 active:scale-[0.98]',
    'disabled:pointer-events-none disabled:opacity-40',
  ],
  {
    variants: {
      variant: {
        primary: 'bg-white text-on-white hover:bg-[var(--white-muted)]',
        accent: 'bg-lime-500 text-on-lime hover:bg-lime-400 active:bg-lime-600',
        ghost: 'bg-surface-3 text-white hover:bg-surface-4',
        outline: 'border border-white/16 bg-transparent text-white hover:bg-surface-3',
        danger: 'bg-danger text-white hover:brightness-110',
      },
      size: {
        sm: 'h-8 px-3.5 text-[13px]',
        md: 'h-10 px-[18px] text-sm',
        lg: 'h-12 px-6 text-base',
      },
      fullWidth: { true: 'w-full', false: '' },
    },
    defaultVariants: { variant: 'primary', size: 'md', fullWidth: false },
  },
);

export interface PillProps
  extends Omit<ComponentPropsWithoutRef<'button'>, 'color'>,
    VariantProps<typeof pill> {
  /** Icon before the label. */
  iconLeft?: ReactNode;
  /** Icon after the label. */
  iconRight?: ReactNode;
}

export function Pill({
  variant,
  size,
  fullWidth,
  iconLeft,
  iconRight,
  className,
  children,
  type = 'button',
  ...props
}: PillProps) {
  return (
    <button
      type={type}
      className={cn(pill({ variant, size, fullWidth }), className)}
      {...(variant === 'accent' ? { 'data-on-lime': '' } : {})}
      {...props}
    >
      {iconLeft}
      {children}
      {iconRight}
    </button>
  );
}
