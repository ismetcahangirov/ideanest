import Image from 'next/image';
import { MediaFrame } from '@ideanest/ui/server';
import { canOptimise } from '../../lib/images/source';
import type { PublicProfile } from '../../lib/profiles/api';

/**
 * Who the profile belongs to — §4.2 P-01 and P-02, issue #274.
 *
 * <h2>It carries the identity and nothing else</h2>
 *
 * A picture, a name, a handle. The counts are on the tabs, where they say how much is behind
 * each one; repeating them here would be the same two numbers twice on one screen, and the
 * second copy is the one that goes stale when somebody adds a `count` to a tab and forgets
 * this file. The biography is on the About tab, because §4.2 gives it a tab (P-06) and a
 * header that also printed it would make that tab a duplicate of the top of the page.
 *
 * <h2>There is no report control here, and the reason is a missing field</h2>
 *
 * §4.9's reporting is built and `POST /v1/users/{id}/report` exists, but it is keyed on an
 * account **identifier** and `PublicProfileResponse` carries only a slug — deliberately, since
 * nothing public on this platform is addressed by an account id. So a control here would have
 * nothing to send. Recorded rather than quietly omitted: the fix is either a slug-addressed
 * report endpoint or an id on the profile projection, and both are a decision rather than an
 * oversight to correct in passing.
 *
 * <h2>The avatar is hand-rolled, which is not a preference</h2>
 *
 * `@ideanest/ui`'s `Avatar` is the component for this and it is not exported from
 * `@ideanest/ui/server` — the lean entry point a Server Component may import. Reaching for
 * the barrel instead fails `next build` outright, naming a component this page never used
 * (`packages/ui/src/server.ts` explains the split), and adding `Avatar` to that entry point
 * is a change to `packages/ui`, which this pull request does not touch. What is here is the
 * same 56-pixel profile size and the same initials fallback docs/ui-kit.md §7.6 specifies,
 * with the box reserved before the image arrives so the heading beside it does not move.
 */

/** "Jane Doe" -> "JD", the same rule `Avatar` uses. */
function initialsOf(name: string): string {
  return name
    .trim()
    .split(/\s+/u)
    .slice(0, 2)
    .map((word) => word[0] ?? '')
    .join('')
    .toUpperCase();
}

export interface ProfileHeaderProps {
  readonly profile: PublicProfile;
}

export function ProfileHeader({ profile }: ProfileHeaderProps) {
  return (
    <header className="flex items-center gap-5">
      <div className="size-14 shrink-0 overflow-hidden rounded-full ring-2 ring-[var(--surface-1)]">
        {profile.avatarUrl === null ? (
          /*
            `role="img"` with a name, rather than two letters read out as text. A screen
            reader announcing "AY" where a face would be is noise; announcing the person's
            name is the information the picture would have carried.
          */
          <span
            role="img"
            aria-label={profile.name}
            className="grid size-full place-items-center bg-surface-3 text-lg font-medium text-white/64"
          >
            {initialsOf(profile.name)}
          </span>
        ) : (
          <MediaFrame ratio="1/1">
            <Image
              src={profile.avatarUrl}
              /* A content image: it is this person, and the heading beside it names them, so
                 the alt says whose picture it is rather than repeating the name alone. */
              alt={`${profile.name}’s profile picture`}
              fill
              sizes="56px"
              unoptimized={!canOptimise(profile.avatarUrl)}
              className="object-cover"
            />
          </MediaFrame>
        )}
      </div>

      <div className="min-w-0">
        <h1 className="truncate text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
          {profile.name}
        </h1>
        {/* The handle is the address of this page, which is worth showing: it is what
            somebody copies when they want to point at this person. */}
        <p className="mt-1 truncate text-sm text-white/40">@{profile.slug}</p>
      </div>
    </header>
  );
}
