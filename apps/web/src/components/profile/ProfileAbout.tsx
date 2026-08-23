import type { PublicProfile } from '../../lib/profiles/api';

/**
 * §4.2's P-06 — the About tab.
 *
 * <h2>Two facts, and it says so when it has one</h2>
 *
 * The biography, when there is one, and the month the account was created. Most accounts have
 * written no biography, so the empty branch is the common case rather than the edge — and it
 * is a sentence rather than a blank panel, because a tab that opens onto nothing reads as a
 * tab that failed to load. `bio` arrives as `null` rather than absent when there is none, so
 * the empty state is an answer this component has received rather than one it is waiting for.
 *
 * <h2>There are no counts here</h2>
 *
 * `GET /v1/users/{slug}` does not carry them and cannot: the two questions belong to the
 * `project` and `pledge` modules, and the `user` module is the one everything else depends on
 * (`lib/profiles/api.ts` carries the argument). A sentence like "3 campaigns created · 12
 * backed" would therefore have to be assembled from the first page of two paginated lists,
 * which is a figure that is right for a small account and quietly wrong for a large one. The
 * tabs print a count only where the list is complete, and this panel prints none at all.
 *
 * <h2>The month, not the day</h2>
 *
 * `joinedAt` is an instant and this prints "March 2025". A day would be a fact about somebody
 * that the page has no reason to publish to the precision the column happens to hold it in,
 * and a time of day would be worse. `lib/time.ts` formats an instant two ways and neither is
 * this one; it is not extended for a single caller, because both of its formats are used on
 * screens where the exact moment is the point and this is the opposite case.
 *
 * <h2>The biography is text, never markup</h2>
 *
 * It is rendered as a string into a `<p>` with `whitespace-pre-line`, so the paragraph breaks
 * somebody typed survive and nothing they typed becomes an element. There is no editor behind
 * this field yet — §4.2's note records that P-01 to P-03 have no write — and the first thing
 * a field like this attracts is a link somebody wants rendered.
 */

const JOINED = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric' });

/** "March 2025", or nothing at all for an instant that will not parse. */
function joinedMonth(iso: string): string | null {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  return JOINED.format(at);
}

export interface ProfileAboutProps {
  readonly profile: PublicProfile;
}

export function ProfileAbout({ profile }: ProfileAboutProps) {
  const joined = joinedMonth(profile.joinedAt);
  const bio = profile.bio === null ? '' : profile.bio.trim();

  return (
    <section className="rounded-2xl border border-white/8 bg-surface-2 p-6 sm:p-8">
      <h2 className="text-lg font-medium tracking-[-0.02em] text-white">About {profile.name}</h2>

      {bio === '' ? (
        <p className="mt-3 max-w-[62ch] text-[15px] leading-relaxed text-white/40">
          {profile.name} has not written anything here.
        </p>
      ) : (
        <p className="mt-3 max-w-[62ch] whitespace-pre-line text-[15px] leading-relaxed text-white/64">
          {bio}
        </p>
      )}

      {joined !== null && (
        <dl className="mt-6 flex flex-col gap-3 border-t border-white/6 pt-6 text-sm">
          <div className="flex flex-wrap gap-x-3">
            <dt className="text-white/40">On IdeaNest since</dt>
            <dd className="text-white/64">{joined}</dd>
          </div>
        </dl>
      )}
    </section>
  );
}
