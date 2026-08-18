import { cva, type VariantProps } from 'class-variance-authority';
import type { ComponentPropsWithoutRef } from 'react';
import { cn } from '../../lib/cn';

const avatar = cva(
  ['shrink-0 rounded-full bg-surface-3 object-cover', 'ring-2 ring-[var(--surface-1)]'],
  {
    variants: {
      size: {
        xs: 'size-6 text-[10px]',
        sm: 'size-7 text-[11px]',
        md: 'size-10 text-sm',
        lg: 'size-14 text-lg',
      },
    },
    defaultVariants: { size: 'md' },
  },
);

/**
 * The pixel side of each size token, docs/ui-kit.md §7.6.
 *
 * Written onto the element as `width` and `height` so the square is reserved
 * from the markup rather than only from the stylesheet. The classes above
 * already size it, but attributes are what a browser has before any CSS has
 * arrived, and an avatar row that reflows once the sheet lands is a layout
 * shift in the one place a reader is already scanning faces.
 */
const AVATAR_PX = { xs: 24, sm: 28, md: 40, lg: 56 } as const;

export interface AvatarProps
  extends Omit<ComponentPropsWithoutRef<'img'>, 'src' | 'alt' | 'width' | 'height'>,
    VariantProps<typeof avatar> {
  src?: string;
  /** Person's name. Used for alt text and the initials fallback. */
  name: string;
}

/** "Jane Doe" -> "JD" */
function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0] ?? '')
    .join('')
    .toUpperCase();
}

export function Avatar({ src, name, size, className, ...props }: AvatarProps) {
  if (!src) {
    return (
      <span
        role="img"
        aria-label={name}
        className={cn(
          avatar({ size }),
          'grid place-items-center font-medium text-white/64',
          className,
        )}
      >
        {initials(name)}
      </span>
    );
  }

  const side = AVATAR_PX[size ?? 'md'];

  return (
    <img
      src={src}
      alt={name}
      width={side}
      height={side}
      loading="lazy"
      decoding="async"
      className={cn(avatar({ size }), className)}
      {...props}
    />
  );
}

/**
 * Overlapping avatar stack. See docs/ui-kit.md §7.6.
 * Spreads apart on hover so individual faces become distinguishable.
 */
export interface AvatarGroupProps extends ComponentPropsWithoutRef<'div'> {
  /** Render at most this many; the remainder collapses into "+N". */
  max?: number;
  total?: number;
}

export function AvatarGroup({ max, total, className, children, ...props }: AvatarGroupProps) {
  const items = Array.isArray(children) ? children : [children];
  const visible = max ? items.slice(0, max) : items;
  const hidden = (total ?? items.length) - visible.length;

  return (
    <div
      className={cn(
        'flex items-center',
        '[&>*]:transition-[margin] [&>*]:duration-200 [&>*]:ease-in-out',
        '[&>*+*]:-ml-2.5 hover:[&>*+*]:-ml-1',
        className,
      )}
      {...props}
    >
      {visible}
      {hidden > 0 && (
        <span
          className={cn(
            'grid size-10 shrink-0 place-items-center rounded-full',
            'bg-surface-3 text-xs font-medium text-white/64 ring-2 ring-[var(--surface-1)]',
          )}
        >
          +{hidden}
        </span>
      )}
    </div>
  );
}
