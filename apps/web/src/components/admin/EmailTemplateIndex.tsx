'use client';

import Link from 'next/link';
import { EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { authorizedFetch } from '../../lib/api/client';
import { errorFrom } from '../../lib/api/problem';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the email templates';

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
export function EmailTemplateIndex() {
  const templates = useConsoleResource(
    async (signal) => {
      const response = await authorizedFetch('/v1/admin/email-templates', { signal });
      if (!response.ok) throw await errorFrom(response);
      return (await response.json()) as TemplateList;
    },
    SUBJECT,
    [],
  );

  if (templates.status === 'signed-out' || templates.status === 'forbidden') {
    return <ConsoleRefusal status={templates.status} subject={SUBJECT} />;
  }

  if (templates.status === 'loading') {
    return (
      <SkeletonGroup label="Loading the email templates">
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
        <InlineAlert variant="danger" title="Something went wrong">
          {templates.error ?? 'The templates could not be read.'}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={templates.reload}>
          Try again
        </Pill>
      </>
    );
  }

  if (templates.data.templates.length === 0) {
    return (
      <EmptyState
        variant="empty"
        title="No templates"
        description="The platform sends no notifications on this deployment, which almost certainly means the message catalogue failed to load."
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
                <Tag>{template.category}</Tag>
                {template.mandatory && <Tag>Transactional</Tag>}
              </span>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  );
}
