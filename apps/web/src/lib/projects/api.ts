import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Money } from '../money';
import type { StoryDocument } from './story';

/**
 * The typed client for the creator's project endpoints.
 *
 * ONE MODULE, ONE PLACE. Every `/v1/projects` call the web application makes
 * belongs here, and the later editor tabs add functions to it rather than
 * starting a second client: two clients drift, and the second one is always the
 * one that forgets that money is a string. The sections below are laid out in
 * the order the epic builds them, so a new endpoint has an obvious home.
 *
 * Shapes come from the epic contract §5 and docs/architecture.md §10.2. Nothing
 * here invents a field.
 *
 * Nullable fields are typed `?: T | null` throughout. The service serialises
 * with `default-property-inclusion: non_null`, so a null `subcategoryId` is
 * ABSENT from the JSON rather than present and null — but the contract's own
 * example prints it as `null`, and a client that treats the two differently
 * breaks the first time that setting changes. Both readings mean "not set".
 */

export type { Money } from '../money';

/* -------------------------------------------------------------------------
 * Lifecycle — contract §5 (#31)
 * ---------------------------------------------------------------------- */

/**
 * Exactly the sixteen states of docs/architecture.md §6.1, no more.
 *
 * The editor only ever renders these; the transitions themselves are the
 * server's business and there is deliberately no client-side copy of the
 * transition table to fall out of step with it.
 */
export type ProjectState =
  | 'DRAFT'
  | 'PRELAUNCH'
  | 'SUBMITTED'
  | 'CHANGES_REQUESTED'
  | 'REJECTED'
  | 'APPROVED'
  | 'SCHEDULED'
  | 'LIVE'
  | 'SUSPENDED'
  | 'CANCELED'
  | 'SUCCESSFUL'
  | 'UNSUCCESSFUL'
  | 'COLLECTING'
  | 'LATE_PLEDGE'
  | 'FULFILLING'
  | 'COMPLETED';

/**
 * The cover image, as three plain fields.
 *
 * INTERIM, and known to be. There is no media table and no uploader yet, so
 * the columns behind this are `cover_image_url`, `cover_image_width`, and
 * `cover_image_height` rather than a reference into a media pipeline
 * (contract §3). The width and height are here because §5.3 makes a cover of at
 * least 1024×576 a submission requirement, and the checklist (#37) cannot check
 * what nothing records. The media epic replaces the three with `main_image_id`
 * under expand-then-contract, at which point this type changes shape once, in
 * one file.
 */
export interface CoverImage {
  url: string;
  width: number;
  height: number;
}

/**
 * The story document, as `PATCH /v1/projects/{id}` carries it.
 *
 * The block union lives in `./story` alongside the operations the editor performs
 * on it, and is re-exported here so that a client reading a `ProjectEdit` does not
 * have to know there are two modules. #35 owns it; the server validates the same
 * schema (`StoryDocuments`).
 *
 * A response is narrowed with `readStoryDocument` rather than cast. The story may
 * have been written by a newer deployment of the editor, and casting would put a
 * block this build does not recognise into the editor's state — where the next
 * autosave would send it back mangled.
 */
export type { StoryBlock, StoryDocument, StorySpan, StorySpans } from './story';

/**
 * A project as the editor sees it — the creator's projection, contract §5.
 *
 * This is the response of every mutation in this file, so one request both
 * changes the project and returns the truth about it. The editor therefore
 * never has to guess what the server did with what it sent.
 */
export interface ProjectEdit {
  id: string;
  slug: string;
  state: ProjectState;
  title: string;
  blurb?: string | null;
  categoryId?: string | null;
  subcategoryId?: string | null;
  goal?: Money | null;
  durationDays?: number | null;
  /** ISO 8601, UTC. */
  scheduledLaunchAt?: string | null;
  launchedAt?: string | null;
  deadline?: string | null;
  story?: StoryDocument | null;
  risks?: string | null;
  coverImage?: CoverImage | null;
  latePledgeEnabled: boolean;
  /**
   * Field names the server will refuse to change, by name — `"goal"`,
   * `"durationDays"`.
   *
   * #31 answers an empty list and #36 fills it in. It is read from the first
   * day regardless, so that the editor disables a locked field the moment the
   * rule exists rather than being rewritten around it. A client-side guess at
   * the same rule is not the plan: immutability after launch (§5.3) is a
   * business rule and it is enforced where the money is.
   */
  lockedFields: readonly string[];
  createdAt: string;
  updatedAt: string;
}

/** True when the server has said this field may no longer be edited. */
export function isLocked(project: ProjectEdit | null | undefined, field: string): boolean {
  return project?.lockedFields.includes(field) ?? false;
}

/**
 * A partial update, with JSON Merge Patch semantics (contract §5).
 *
 * An absent key leaves the field alone; an explicit `null` clears it. That
 * distinction is why every optional field here is `T | null` rather than just
 * `T`, and why the autosave path builds patches by field rather than sending
 * the whole form: sending the whole form would overwrite the story with
 * whatever the basics tab happened to be holding.
 */
export interface ProjectPatch {
  title?: string;
  blurb?: string | null;
  categoryId?: string | null;
  subcategoryId?: string | null;
  goal?: Money | null;
  durationDays?: number | null;
  scheduledLaunchAt?: string | null;
  story?: StoryDocument | null;
  risks?: string | null;
  coverImage?: CoverImage | null;
  latePledgeEnabled?: boolean;
}

const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;

async function readProject(response: Response): Promise<ProjectEdit> {
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as ProjectEdit;
}

/**
 * Starts a draft. The title is the only thing creation needs; everything else
 * is edited afterwards, which is what makes the editor autosave-shaped rather
 * than a long form with a submit button at the bottom.
 */
export async function createProject(
  input: { title: string },
  signal?: AbortSignal,
): Promise<ProjectEdit> {
  return readProject(
    await authorizedFetch('/v1/projects', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(input),
      signal,
    }),
  );
}

/** The creator's projection of one project, for the editor. */
export async function getProjectEdit(id: string, signal?: AbortSignal): Promise<ProjectEdit> {
  const path = `/v1/projects/${encodeURIComponent(id)}/edit`;
  return readProject(await authorizedFetch(path, { signal }));
}

/**
 * Applies a partial update and returns the project as it now stands.
 *
 * The body has JSON Merge Patch semantics but is sent as `application/json`,
 * not `application/merge-patch+json`. The semantics are the server's contract;
 * the media type would be a second contract, and a Spring controller that
 * consumes the default type answers 415 to the specialised one. Declaring a
 * type the service has not agreed to would turn every autosave into a
 * negotiation failure.
 */
export async function patchProject(
  id: string,
  patch: ProjectPatch,
  signal?: AbortSignal,
): Promise<ProjectEdit> {
  return readProject(
    await authorizedFetch(`/v1/projects/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify(patch),
      signal,
    }),
  );
}

/* -------------------------------------------------------------------------
 * Taxonomy
 *
 * `GET /v1/categories` is in docs/architecture.md §10.2 under discovery, and
 * the `categories` / `subcategories` tables are seeded by #31's migration — but
 * no sub-issue of this epic owns the endpoint, so it may well answer 404 until
 * the discovery epic lands. The basics form treats that as "the list is
 * unavailable" and keeps saving everything else, rather than blocking the
 * editor on a read it does not strictly need.
 * ---------------------------------------------------------------------- */

export interface Subcategory {
  id: string;
  slug: string;
  name: string;
}

export interface Category extends Subcategory {
  subcategories: readonly Subcategory[];
}

/**
 * The taxonomy the seed data holds `name_az` and `name_en` for.
 *
 * Which of them reaches the client depends on whether the endpoint localises
 * through `Accept-Language` (§10.3) or returns both columns, and that decision
 * is not this issue's to make. The read below accepts either and falls back to
 * the slug, so a naming choice made later is a change to one function rather
 * than a broken field.
 */
interface RawTaxon {
  id: string;
  slug: string;
  name?: string;
  nameEn?: string;
  nameAz?: string;
  subcategories?: readonly RawTaxon[];
}

function taxonName(raw: RawTaxon): string {
  return raw.name ?? raw.nameEn ?? raw.nameAz ?? raw.slug;
}

export async function listCategories(signal?: AbortSignal): Promise<readonly Category[]> {
  const response = await authorizedFetch('/v1/categories', { signal });
  if (!response.ok) throw await errorFrom(response);

  const raw = (await response.json()) as readonly RawTaxon[];

  return raw.map((category) => ({
    id: category.id,
    slug: category.slug,
    name: taxonName(category),
    subcategories: (category.subcategories ?? []).map((sub) => ({
      id: sub.id,
      slug: sub.slug,
      name: taxonName(sub),
    })),
  }));
}

/* -------------------------------------------------------------------------
 * Story versions — contract §5 (#35)
 *
 * There is no function here that WRITES a version, and that is the contract
 * rather than an omission: the story is saved through `patchProject` with
 * everything else, and a version is a consequence of that save. The server
 * decides when one is due — the document changed and the newest version is older
 * than five minutes — so a client that could ask for one would be a second,
 * disagreeing answer to the same question.
 * ---------------------------------------------------------------------- */

/** One row of the history. Without the document; see `getStoryVersion`. */
export interface StoryVersionSummary {
  number: number;
  /** ISO 8601, UTC. */
  createdAt: string;
  authorId: string;
  /**
   * Characters of prose, counted as §5.3 counts them.
   *
   * The one number that makes a list of timestamps usable: "3 minutes ago, 1,240
   * characters" tells a creator which version came before they deleted a section,
   * and a timestamp alone does not.
   */
  characters: number;
}

export interface StoryVersionDetail extends StoryVersionSummary {
  /**
   * Unnarrowed on purpose. Callers pass it through `readStoryDocument`, which
   * refuses a document written against a schema this build does not know rather
   * than letting it into the editor's state.
   */
  document: unknown;
}

/**
 * The kept versions of a story, newest first.
 *
 * Unpaged, because retention caps the list at fifty rows server-side. A `Pagination`
 * control for a second page that cannot exist would be an interface built for a
 * case the service has ruled out.
 */
export async function listStoryVersions(
  projectId: string,
  signal?: AbortSignal,
): Promise<readonly StoryVersionSummary[]> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/story/versions`;
  const response = await authorizedFetch(path, { signal });
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as readonly StoryVersionSummary[];
}

/** One version and its document, so it can be read before anything is replaced. */
export async function getStoryVersion(
  projectId: string,
  number: number,
  signal?: AbortSignal,
): Promise<StoryVersionDetail> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/story/versions/${number}`;
  const response = await authorizedFetch(path, { signal });
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as StoryVersionDetail;
}

/**
 * Makes an older version the current story, and answers the whole project.
 *
 * A write, and a destructive one — it replaces whatever is in the editor. The
 * server preserves the replaced document as a version first, which is what makes
 * offering the action defensible; the interface still asks before doing it, because
 * "the story you have been writing for an hour is gone" is not a thing to discover
 * from a page that has already changed.
 */
export async function restoreStoryVersion(
  projectId: string,
  number: number,
  signal?: AbortSignal,
): Promise<ProjectEdit> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/story/versions/${number}/restore`;
  return readProject(await authorizedFetch(path, { method: 'POST', signal }));
}

/* -------------------------------------------------------------------------
 * Still to come. Each of these is a function in THIS module, not a new client.
 *
 *   #32 items and reward tiers   POST/PATCH/DELETE /v1/items, /v1/rewards,
 *                                reorder, duplicate, shipping rules
 *   #37 checklist                GET /v1/projects/{id}/checklist
 *   #39 pre-launch and follows   POST/DELETE /v1/projects/{id}/prelaunch
 *   #31 submit, launch, cancel   POST /v1/projects/{id}/submit | launch | cancel
 *
 * The story, risks, and cover fields of `ProjectPatch` are already here because
 * they are written through the same autosave path; the tabs that own them add
 * their own endpoints, not their own patch type.
 * ---------------------------------------------------------------------- */
