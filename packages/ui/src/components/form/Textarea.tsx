import type { VariantProps } from 'class-variance-authority';
import { useLayoutEffect, useRef, type ComponentPropsWithoutRef } from 'react';
import { cn } from '../../lib/cn';
import { useFieldControl } from './Field';
import { inputSkin } from './inputSkin';

/**
 * Multi-line input, same skin as `TextInput`. See docs/ui-kit.md §7.13.
 *
 * `autoGrow` resizes by writing `style.height` directly rather than by
 * transitioning it. Height is not a compositor property: animating it forces
 * layout on every frame, and the rule is transform and opacity only
 * (docs/motion-system.md §8). A field that eases open while someone is typing
 * into it is also just annoying.
 */
export interface TextareaProps
  extends Omit<ComponentPropsWithoutRef<'textarea'>, 'size' | 'color'>,
    VariantProps<typeof inputSkin> {
  /** Grow to fit the content instead of scrolling. */
  autoGrow?: boolean;
}

export function Textarea({
  size,
  invalid,
  autoGrow = false,
  rows = 4,
  className,
  ...props
}: TextareaProps) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const aria = useFieldControl({
    id: props.id,
    'aria-describedby': props['aria-describedby'],
    invalid,
    required: props.required,
  });

  const value = props.value;
  useLayoutEffect(() => {
    const el = ref.current;
    if (!autoGrow || !el) return;
    // Collapse before measuring, or the box can only ever get taller.
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, [autoGrow, value]);

  return (
    <textarea
      ref={ref}
      rows={rows}
      {...props}
      onInput={(event) => {
        if (autoGrow) {
          const el = event.currentTarget;
          el.style.height = 'auto';
          el.style.height = `${el.scrollHeight}px`;
        }
        props.onInput?.(event);
      }}
      id={aria.id}
      aria-describedby={aria['aria-describedby']}
      aria-invalid={aria['aria-invalid']}
      required={aria.required}
      className={cn(
        inputSkin({ size, invalid: aria.invalid }),
        // The skin fixes a control height; a textarea sets its own.
        'h-auto min-h-24 py-2.5',
        autoGrow ? 'resize-none overflow-hidden' : 'resize-y',
        className,
      )}
    />
  );
}
