import type { Metadata } from 'next';
import { SupportConsole } from '../../../components/admin/SupportConsole';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-10: tickets with user context and action history — issue #310.
 *
 * <p>A support conversation is read beside the pledge it is about and every other ticket the
 * same person has raised, which is the half a shared mailbox cannot do — and the reason the
 * platform needed a store of its own.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Support',
  description: 'Tickets with the account context they are about and the history of what was done.',
});

export default function SupportPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Support
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Most urgent first, oldest first within a priority. There is no public form behind this yet:
        somebody writes in, and a member of staff records the conversation against their account.
      </p>

      <div className="mt-8">
        <SupportConsole />
      </div>
    </div>
  );
}
