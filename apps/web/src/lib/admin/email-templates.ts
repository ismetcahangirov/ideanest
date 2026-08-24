import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-15, third verb: editing the copy the platform sends — §12.3, issue #315.
 *
 * <h2>What #315 was blocked on, and what the answer turned out to be</h2>
 *
 * "No template store, and no answer to who may rewrite a payment-failure notice." The store
 * is V52. The second half has two parts and only one of them is a role:
 *
 * - **Who.** `CONFIGURE_PLATFORM`, which only an administrator holds.
 * - **What may not be removed.** A payment-failure notice that no longer says which card was
 *   declined is worse than no override at all, and *no role check catches that* — the
 *   administrator editing it is exactly the person allowed to. So the shipped copy's
 *   placeholders are extracted and an override must keep every one of them.
 *
 * This module carries the second rule into the screen: `requiredPlaceholders` comes back with
 * the draft, and {@link missingPlaceholders} is what lets the editor say which are gone
 * before the service refuses.
 *
 * <h2>The subject and the first paragraph, and nothing else</h2>
 *
 * The headline, the button label and a type's conditional second paragraph stay in the
 * shipped catalogue. A button with no label is a broken email rather than a badly worded one,
 * and a second paragraph that only appears for some recipients is copy an editor cannot see
 * the effect of.
 */

/** One stored version. Nothing is edited in place; each edit appends. */
export interface EmailTemplateVersion {
  id: string;
  templateKey: string;
  locale: string;
  version: number;
  subject: string;
  body: string;
  requiredPlaceholders: string[];
  /** Exactly one version per template and locale may be live. */
  live: boolean;
  note?: string | null;
  createdAt: string;
  createdBy: string;
}

/**
 * What the editor opens with.
 *
 * Both the shipped copy and the override, because an editor showing only the current text
 * gives nobody a way to see what they changed it from — and the shipped copy is also the
 * preview of withdrawing the override.
 */
export interface EmailTemplateDraft {
  type: string;
  locale: string;
  shippedSubject: string;
  shippedBody: string;
  /** The `{n}` argument indices an override must keep. */
  requiredPlaceholders: string[];
  /** Null when the platform is sending the shipped copy. */
  override?: EmailTemplateVersion | null;
}

export interface EmailTemplateHistory {
  versions: EmailTemplateVersion[];
}

/** The shipped copy, the override if there is one, and the placeholders that must stay. */
export async function readTemplateDraft(
  type: string,
  signal?: AbortSignal,
): Promise<EmailTemplateDraft> {
  const response = await authorizedFetch(
    `/v1/admin/email-templates/${encodeURIComponent(type)}/copy`,
    { signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as EmailTemplateDraft;
}

/** Every version ever written, newest first. */
export async function readTemplateHistory(
  type: string,
  signal?: AbortSignal,
): Promise<EmailTemplateHistory> {
  const response = await authorizedFetch(
    `/v1/admin/email-templates/${encodeURIComponent(type)}/versions`,
    { signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as EmailTemplateHistory;
}

/** Writes a new version and makes it live. */
export async function editTemplate(
  type: string,
  subject: string,
  body: string,
  note: string | null,
  signal?: AbortSignal,
): Promise<EmailTemplateVersion> {
  const response = await authorizedFetch(
    `/v1/admin/email-templates/${encodeURIComponent(type)}/copy`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ subject, body, note }),
      signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as EmailTemplateVersion;
}

/** Takes the override out of service, so the shipped copy renders again. The versions stay. */
export async function withdrawTemplate(type: string, signal?: AbortSignal): Promise<void> {
  const response = await authorizedFetch(
    `/v1/admin/email-templates/${encodeURIComponent(type)}/copy`,
    { method: 'DELETE', signal },
  );
  if (!response.ok) throw await errorFrom(response);
}

/**
 * A `MessageFormat` argument index.
 *
 * Matches `{0}` and `{1,number,#.##}` alike: the index is what has to survive an edit, and
 * the format is what an editor is allowed to change. The same expression the service uses,
 * so the screen and the check agree about what counts.
 */
const PLACEHOLDER = /\{(\d+)/g;

/** Every argument index used across a subject and a body. */
export function placeholdersIn(subject: string, body: string): string[] {
  const found = new Set<string>();
  for (const text of [subject, body]) {
    for (const match of text.matchAll(PLACEHOLDER)) {
      const index = match[1];
      if (index !== undefined) found.add(index);
    }
  }
  return [...found];
}

/**
 * Which required placeholders a draft has dropped.
 *
 * Checked in the browser so the editor can say so as somebody types, and checked again in
 * the service because a browser check is a courtesy rather than a rule. Empty means the
 * service will accept it.
 */
export function missingPlaceholders(
  required: readonly string[],
  subject: string,
  body: string,
): string[] {
  const present = new Set(placeholdersIn(subject, body));
  return required.filter((index) => !present.has(index));
}
