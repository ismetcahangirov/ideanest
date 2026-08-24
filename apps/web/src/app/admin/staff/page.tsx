import type { Metadata } from 'next';
import { StaffRoles } from '../../../components/admin/StaffRoles';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's role model, as a screen — issue #295.
 *
 * <p>The first thing it shows is what the reader may do, because the commonest question a
 * member of staff has about a console is why a screen they were told about is not on their
 * rail. The roster below it needs `ADMINISTER_STAFF`, which only an administrator holds.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Staff and roles',
  description: 'Who works here, what each role confers, and what your own account may do.',
});

export default function StaffPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Staff and roles
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Roles are additive: an account holds the union of every role granted to it, and nothing
        here takes a capability away. Granting one is audited under your name.
      </p>

      <div className="mt-8">
        <StaffRoles />
      </div>
    </div>
  );
}
