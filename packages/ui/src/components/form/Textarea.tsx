import type { VariantProps } from 'class-variance-authority';
import { useLayoutEffect, useRef, type ComponentPropsWithoutRef, type Ref } from 'react';
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
  /**
   * The element itself.
   *
   * Declared rather than inherited, because `ComponentPropsWithoutRef` excludes it
   * and this component keeps a ref of its own for `autoGrow` — so a caller's ref
   * would either be dropped or would replace the internal one depending on the
   * order the props happened to be spread in. Both are handed the node below.
   *
   * A caller needs it for the one thing React cannot express: restoring the caret
   * after a controlled value changed underneath it. The campaign story editor
   * applies bold to a selection, which means a new value and then a selection range
   * that only exists once the DOM holds it.
   */
  ref?: Ref<HTMLTextAreaElement>;
}

export function Textarea({
  size,
  invalid,
  autoGrow = false,
  rows = 4,
  className,
  ref: forwarded,
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
      ref={(node) => {
        ref.current = node;
        if (typeof forwarded === 'function') {
          forwarded(node);
          return;
        }
        if (forwarded != null) forwarded.current = node;
      }}
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
