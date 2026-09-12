'use client';

import { Eye, EyeOff } from 'lucide-react';

/**
 * The control that lets somebody read the password they are typing — issue #457.
 *
 * <h2>Why a password field is allowed to be read at all</h2>
 *
 * Masking protects against somebody standing behind you. It is not a property of the form:
 * the value is in the DOM either way, and nothing about a `type="password"` makes the
 * credential safer in transit, in memory or in a password manager. What it does cost is the
 * ability to proofread, and that cost is paid twice on these two screens — a typo on
 * `/register` becomes an account whose password nobody knows, and a typo on `/sign-in` is an
 * attempt against §17.3's five-per-fifteen-minutes on a form that deliberately will not say
 * which half was wrong.
 *
 * So the mask stays the default and the reader is given the choice, which is the trade every
 * platform password field has settled on.
 *
 * <h2>The state is not carried anywhere</h2>
 *
 * It is `useState` in the form that draws the field, and it is deliberately not lifted, not
 * stored and not remembered between visits. A revealed password that survived a navigation
 * would be a password left legible on a screen whose owner has walked away from it — the one
 * thing masking is actually for.
 *
 * <h2>`aria-pressed`, and a name that changes with it</h2>
 *
 * docs/ui-kit.md §9.2 — the icon swap cannot be the only carrier of the state. This is a
 * toggle button, so the platform announces "pressed"/"not pressed" without a live region, and
 * the accessible name says which action the control offers next ("Show password" while hidden,
 * "Hide password" while shown). The glyph is `aria-hidden`, so the name is not read twice.
 *
 * <h2>It stays in the tab order</h2>
 *
 * The people most likely to need to proofread a password are the least likely to be holding a
 * mouse, so this is a real `<button>` with a visible focus ring — including on the lime
 * outline the rest of the authentication screens use. `tabIndex={-1}` is the usual shortcut
 * here and it takes the control away from exactly the reader it was built for.
 *
 * <h2>Motion</h2>
 *
 * docs/motion-system.md §5 gives authentication "None — 150ms colour on controls". This
 * transitions colour and nothing else: no scale, no transform. `IconButton` presses inward on
 * `:active`, which is why the markup is written out here rather than borrowed from it.
 *
 * <h2>It is one step lighter than the slot it sits in</h2>
 *
 * `TextInput` draws its trailing slot in `--text-tertiary`, which is right for the decoration
 * that slot was built for and is 3.8:1 — below §9.1's threshold for anything inside a control.
 * So this sets `--text-secondary` on itself and lifts to white on hover;
 * `accessibility-rules.test.ts` fails the suite on the other choice.
 */
export interface PasswordRevealProps {
  /** Whether the field beside this control is currently showing its value. */
  readonly revealed: boolean;
  readonly onToggle: () => void;
  /** The name while hidden — the action offered, not the state. */
  readonly showLabel: string;
  /** The name while shown. */
  readonly hideLabel: string;
}

export function PasswordReveal({ revealed, onToggle, showLabel, hideLabel }: PasswordRevealProps) {
  const Icon = revealed ? EyeOff : Eye;

  return (
    <button
      type="button"
      /*
       * `type="button"` is load-bearing inside a form: the default is `submit`, and a reveal
       * control that submitted the sign-in form would spend an attempt against the rate limit
       * every time somebody checked what they had typed.
       */
      aria-pressed={revealed}
      aria-label={revealed ? hideLabel : showLabel}
      onClick={onToggle}
      className="grid size-6 place-items-center rounded-sm text-white/64 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
    >
      <Icon aria-hidden="true" className="size-4" />
    </button>
  );
}
