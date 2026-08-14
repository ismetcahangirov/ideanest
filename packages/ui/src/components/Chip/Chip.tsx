import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import { cn } from '../../lib/cn';

/**
 * Filter chip. See docs/ui-kit.md §7.3.
 *
 * The selected state is WHITE, not lime. Filters appear in rows, and lime
 * carries the meaning "urgent" — which is meaningless for a filter.
 */
export interface ChipProps extends Omit<ComponentPropsWithoutRef<'button'>, 'color'> {
  active?: boolean;
  /** Result count rendered after the label. */
  count?: number;
  icon?: ReactNode;
}

export function Chip({
  active = false,
  count,
  icon,
  className,
  children,
  type = 'button',
  ...props
}: ChipProps) {
  return (
    <button
      type={type}
      aria-pressed={active}
      className={cn(
        'inline-flex h-[34px] shrink-0 items-center gap-1.5 whitespace-nowrap',
        'rounded-full px-4 text-[13px] font-medium',
        'transition-[background-color,color,border-color] duration-150 ease-in-out',
        active
          ? 'border border-transparent bg-white text-on-white'
          : 'border border-white/8 bg-surface-2 text-white/64 hover:bg-surface-3 hover:text-white',
        className,
      )}
      {...props}
    >
      {icon}
      {children}
      {count !== undefined && (
        <span className={cn('tabular-nums', active ? 'text-on-white/50' : 'text-white/40')}>
          {count}
        </span>
      )}
    </button>
  );
}

/**
 * Horizontally scrolling chip row.
 *
 * The right edge fades rather than cutting off, so "there is more" is legible
 * without a scrollbar.
 */
export interface ChipRowProps extends ComponentPropsWithoutRef<'div'> {
  /** Right-edge fade mask. Disable when every chip already fits. */
  fadeEdge?: boolean;
}

export function ChipRow({ fadeEdge = true, className, children, ...props }: ChipRowProps) {
  return (
    <div
      role="group"
      className={cn(
        'scrollbar-none flex gap-2 overflow-x-auto',
        fadeEdge && 'fade-edge-r',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}
