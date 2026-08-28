'use client';

import { Link } from '../../i18n/navigation';
import { EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { authorizedFetch } from '../../lib/api/client';
import { errorFrom } from '../../lib/api/problem';
import type { EmailTemplateIndexCopy } from '../../lib/i18n/admin/platform-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

/** One notification type, as `GET /v1/admin/email-templates` lists it (#86). */
interface TemplateSummary {
  type: string;
  category: string;
  /**
   * Whether a recipient can switch it off.
   *
   * A mandatory template is a transactional notice — a payment failure, a receipt — and it
   * is the set §12.3's open question about editing is really about. The screen marks them.
   */
  mandatory: boolean;
}

interface TemplateList {
  templates: TemplateSummary[];
}

/**
 * The front door of §4.11's AD-15 — issue #315.
 *
 * <h2>Why a list page exists rather than a dropdown</h2>
 *
 * There are tens of notification types and each one is a separate piece of copy with its own
 * history. A `<select>` would make "which of these has somebody rewritten" unanswerable
 * without opening every entry, and that is the question an administrator arrives with — so
 * the list is the screen and the editor is a route under it.
 *
 * <h2>Mandatory templates are marked and are not treated differently here</h2>
 *
 * They are the transactional notices — the ones §12.3's open question about editing is
 * actually about. What protects them is not this screen: it is that the service refuses an
 * override which drops a placeholder the shipped copy carries, so a payment-failure notice
 * cannot stop saying which card was declined. Marking them is so that somebody editing one
 * knows what they are holding.
 */
export interface EmailTemplateIndexProps {
  readonly copy: EmailTemplateIndexCopy;
}

export function EmailTemplateIndex({ copy }: EmailTemplateIndexProps) {
  const templates = useConsoleResource(
    async (signal) => {
      const response = await authorizedFetch('/v1/admin/email-templates', { signal });
      if (!response.ok) throw await errorFrom(response);
      return (await response.json()) as TemplateList;
    },
    copy.subject,
    copy.refusals,
    [],
  );

  if (templates.status === 'signed-out' || templates.status === 'forbidden') {
    return <ConsoleRefusal status={templates.status} subject={copy.subject} copy={copy.refusals} />;
  }

  if (templates.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingList}>
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
              <Skeleton height="1rem" width="40%" />
            </div>
          ))}
        </div>
      </SkeletonGroup>
    );
  }

  if (templates.status === 'failed' || templates.data === null) {
    return (
      <>
        <InlineAlert variant="danger" title={copy.errorTitle}>
          {templates.error ?? copy.readFailed}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={templates.reload}>
          {copy.tryAgain}
        </Pill>
      </>
    );
  }

  if (templates.data.templates.length === 0) {
    return (
      <EmptyState
        variant="empty"
        title={copy.emptyTitle}
        description={copy.emptyBody}
      />
    );
  }

  return (
    <ul className="flex list-none flex-col gap-2">
      {templates.data.templates.map((template) => (
        <li key={template.type}>
          <Link
            href={`/admin/email-templates/${encodeURIComponent(template.type)}`}
            className="block rounded-lg border border-white/8 bg-surface-1 p-4 transition-colors duration-150 ease-in-out hover:border-white/16 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <p className="font-mono text-sm text-white">{template.type}</p>
              <span className="flex items-center gap-2">
                {/* The wire value stands in for a category the catalogue has not been
                    taught, which is readable and is the honest failure of the two. */}
                <Tag>{copy.category[template.category] ?? template.category}</Tag>
                {template.mandatory && <Tag>{copy.transactional}</Tag>}
              </span>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  );
}
