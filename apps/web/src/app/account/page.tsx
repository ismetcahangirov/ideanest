import { redirect } from 'next/navigation';

/**
 * `/account` redirects to the first screen under it, exactly as `/settings` does.
 *
 * Saved projects rather than surveys: it is the screen that is worth reading when nothing is
 * owed, and a landing page that opens on an empty survey list reads as an account with
 * nothing in it.
 */
export default function AccountIndexPage() {
  redirect('/account/saved');
}
