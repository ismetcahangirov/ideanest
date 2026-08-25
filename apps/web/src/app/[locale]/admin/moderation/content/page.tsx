import type { Metadata } from 'next';
import { ContentReportQueue } from '../../../../../components/admin/ContentReportQueue';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

/**
 * §4.11's AD-09: the comment and update moderation queue — issue #297.
 *
 * <p>Half of this queue had no intake until now: §10.2 gave an update no report route, so
 * `PROJECT_UPDATE` was a value the taxonomy carried and nothing could write. #83 built the
 * table and #297 published the route, which cost a controller method and no migration.
 *
 * <p>The route is `moderation/content` rather than `moderation/comments`, because it is both
 * surfaces — and it sits beside `[reportId]`, which is safe: Next resolves a static segment
 * before a dynamic one, and a report identifier is a UUID.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Content reports',
  description: 'Complaints about comments and campaign updates, oldest first.',
});

export default function ContentModerationPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Content reports
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Complaints about what somebody wrote on a campaign, oldest first. Deciding one records a
        judgement about the complaint — taking the comment or update down is a separate action,
        by somebody who can see it in context.
      </p>

      <div className="mt-8">
        <ContentReportQueue />
      </div>
    </div>
  );
}
