import { getTranslations } from 'next-intl/server';
import { type CheckoutCopy, checkoutCopyFrom } from './checkout-copy';
import {
  type FailureCopy,
  type FooterCopy,
  type ShellCopy,
  failureCopyFrom,
  footerCopyFrom,
  shellCopyFrom,
} from './shell-copy';

/**
 * The shell's copy, read from the request's catalogue — issue #324.
 *
 * <h2>Why this is a second file</h2>
 *
 * `shell-copy.ts` holds the types and the pure builders and imports nothing from
 * `next-intl/server`. That split is what lets a component test build the identical object out
 * of `messages/*.json` — asserting against the words the application will draw rather than
 * against words retyped into the test, which is a test that passes whatever the catalogue
 * says. Keeping the server call here means importing the shape never drags a server module
 * into a client bundle.
 */
export async function shellCopy(): Promise<ShellCopy> {
  return shellCopyFrom(await getTranslations('shell'));
}

export async function footerCopy(): Promise<FooterCopy> {
  return footerCopyFrom(await getTranslations('shell'));
}

export async function failureCopy(): Promise<FailureCopy> {
  return failureCopyFrom(await getTranslations('shell'));
}

/** The checkout's words. `checkout-copy.ts` explains why the whole of it is one prop. */
export async function checkoutCopy(): Promise<CheckoutCopy> {
  return checkoutCopyFrom(await getTranslations('checkout'));
}
