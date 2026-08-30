'use client';

import { useMemo, useState } from 'react';
import {
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Tag,
  Textarea,
  TextInput,
} from '@ideanest/ui';
import {
  editTemplate,
  missingPlaceholders,
  readTemplateDraft,
  readTemplateHistory,
  withdrawTemplate,
} from '../../lib/admin/email-templates';
import { consoleMessageFor } from '../../lib/admin/refusals';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import type { EmailTemplateEditorCopy } from '../../lib/i18n/admin/platform-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

export interface EmailTemplateEditorProps {
  /** The `NotificationType` being edited, as the service names it. */
  readonly type: string;
  readonly copy: EmailTemplateEditorCopy;
}

/**
 * §4.11's AD-15, third verb — §12.3, issue #315.
 *
 * <h2>The decision this screen encodes</h2>
 *
 * #315 was blocked on "no template store, and no answer to who may rewrite a payment-failure
 * notice". The store is a table. The second half has two parts and only one of them is a role:
 *
 * - **Who** is `CONFIGURE_PLATFORM`, which only an administrator holds, and the service checks.
 * - **What may not be removed** is the part no role check catches, because the administrator
 *   editing a payment-failure notice is exactly the person allowed to. A notice that no longer
 *   says which card was declined is worse than no override at all — so the shipped copy&apos;s
 *   placeholders must survive the edit, and this screen names the missing ones as somebody
 *   types rather than letting them find out on submit.
 *
 * <h2>The shipped copy stays on the screen</h2>
 *
 * It is what somebody is changing *from*, and it is what withdrawing an override returns to —
 * so it is also the preview of the undo. An editor showing only the current text gives nobody
 * a way to see what they did.
 *
 * <h2>Two fields, not the whole message</h2>
 *
 * The subject and the first paragraph. The headline, the button label and a type&apos;s
 * conditional second paragraph stay in the shipped catalogue: a button with no label is a
 * broken email rather than a badly worded one, and a paragraph that appears for only some
 * recipients is copy an editor cannot see the effect of.
 */
export function EmailTemplateEditor({ type, copy }: EmailTemplateEditorProps) {
  const draft = useConsoleResource(
    (signal) => readTemplateDraft(type, signal),
    copy.subject,
    copy.refusals,
    [type],
  );
  const history = useConsoleResource(
    (signal) => readTemplateHistory(type, signal),
    copy.historySubject,
    copy.refusals,
    [type],
  );

  const [subject, setSubject] = useState<string | null>(null);
  const [body, setBody] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  // The draft loads after the first render, so the fields start as null and fall back to
  // whatever the service says is live -- the override if there is one, the shipped copy
  // otherwise. Seeding state inside an effect would blank whatever somebody had typed the
  // moment a reload finished.
  const currentSubject = subject ?? draft.data?.override?.subject ?? draft.data?.shippedSubject ?? '';
  const currentBody = body ?? draft.data?.override?.body ?? draft.data?.shippedBody ?? '';

  const missing = useMemo(
    () => missingPlaceholders(draft.data?.requiredPlaceholders ?? [], currentSubject, currentBody),
    [draft.data?.requiredPlaceholders, currentSubject, currentBody],
  );

  if (draft.status === 'signed-out' || draft.status === 'forbidden') {
    return <ConsoleRefusal status={draft.status} subject={copy.subject} copy={copy.refusals} />;
  }

  async function act(work: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      await work();
      draft.reload();
      history.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  if (draft.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingList}>
        <Skeleton height="1rem" width="40%" />
        <Skeleton height="5rem" width="100%" className="mt-4" />
      </SkeletonGroup>
    );
  }

  if (draft.status === 'failed' || draft.data === null) {
    return (
      <>
        <InlineAlert variant="danger" title={copy.errorTitle}>
          {draft.error ?? copy.readFailed}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={draft.reload}>
          {copy.tryAgain}
        </Pill>
      </>
    );
  }

  const overridden = draft.data.override != null;

  return (
    <div className="flex flex-col gap-8">
      <InlineAlert
        variant={overridden ? 'warning' : 'info'}
        title={overridden ? copy.overriddenTitle : copy.shippedTitle}
      >
        {overridden
          ? fillPlaceholders(copy.overriddenBody, {
              version: String(draft.data.override?.version ?? ''),
            })
          : copy.shippedBody}
      </InlineAlert>

      <section aria-labelledby="shipped-heading">
        <h2 id="shipped-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.shippedHeading}
        </h2>
        <div className="mt-3 rounded-lg border border-white/8 bg-surface-1 p-4">
          <p className="text-sm text-white/80">{draft.data.shippedSubject}</p>
          <p className="mt-2 whitespace-pre-wrap text-sm text-white/64">{draft.data.shippedBody}</p>
        </div>
      </section>

      <section aria-labelledby="edit-heading">
        <h2 id="edit-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.yourHeading}
        </h2>

        {draft.data.requiredPlaceholders.length > 0 && (
          <p className="mt-2 text-xs text-white/48">
            {copy.mustKeep}{' '}
            {draft.data.requiredPlaceholders.map((index) => (
              <code key={index} className="mr-1 font-mono">{`{${index}}`}</code>
            ))}
            {copy.mustKeepWhy}
          </p>
        )}

        <div className="mt-3 flex flex-col gap-3">
          <Field label={copy.subjectLabel}>
            <TextInput
              value={currentSubject}
              onChange={(event) => setSubject(event.target.value)}
              maxLength={300}
            />
          </Field>

          <Field label={copy.bodyLabel}>
            <Textarea
              rows={4}
              value={currentBody}
              onChange={(event) => setBody(event.target.value)}
              maxLength={50000}
            />
          </Field>

          <Field label={copy.whyLabel} hint={copy.whyHint}>
            <TextInput value={note} onChange={(event) => setNote(event.target.value)} maxLength={2000} />
          </Field>
        </div>

        {missing.length > 0 && (
          <InlineAlert variant="danger" title={copy.missingTitle} className="mt-4">
            {fillPlaceholders(copy.missingBody, {
              placeholders: missing.map((index) => `{${index}}`).join(', '),
            })}
          </InlineAlert>
        )}

        <div className="mt-4 flex flex-wrap gap-2">
          <Pill
            variant="outline"
            size="sm"
            disabled={busy || missing.length > 0 || currentSubject.trim() === '' || currentBody.trim() === ''}
            onClick={() =>
              void act(async () => {
                await editTemplate(
                  type,
                  currentSubject.trim(),
                  currentBody.trim(),
                  note.trim() === '' ? null : note.trim(),
                );
                setNote('');
                setSaved(copy.savedNew);
              })
            }
          >
            {busy ? copy.saving : copy.saveNew}
          </Pill>

          {overridden && (
            <Pill
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() =>
                void act(async () => {
                  await withdrawTemplate(type);
                  setSubject(null);
                  setBody(null);
                  setSaved(copy.savedWithdrawn);
                })
              }
            >
              {copy.withdraw}
            </Pill>
          )}
        </div>

        {saved && (
          <InlineAlert variant="success" title={copy.doneTitle} className="mt-4">
            {saved}
          </InlineAlert>
        )}
        {error && (
          <InlineAlert variant="danger" title={copy.failedTitle} className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>

      {history.status === 'ready' && history.data !== null && history.data.versions.length > 0 && (
        <section aria-labelledby="history-heading">
          <h2 id="history-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
            {copy.versionsHeading}
          </h2>
          <ul className="mt-3 flex list-none flex-col gap-2">
            {history.data.versions.map((version) => (
              <li key={version.id} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="text-sm text-white/80">
                    {fillPlaceholders(copy.versionLine, {
                      version: String(version.version),
                      date: version.createdAt.slice(0, 10),
                    })}
                  </p>
                  {version.live && <Tag>{copy.live}</Tag>}
                </div>
                <p className="mt-2 text-sm text-white/64">{version.subject}</p>
                {version.note && <p className="mt-1 text-xs text-white/40">{version.note}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
