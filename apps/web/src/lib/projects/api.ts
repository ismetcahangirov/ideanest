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
 * Items — docs/architecture.md §10.2 (#32, #34)
 *
 * The atomic things a campaign produces. Items come BEFORE tiers, which is the
 * order §4.6 puts them in: a tier is a selection of items with quantities, so
 * there is nothing to compose until these exist.
 * ---------------------------------------------------------------------- */

/**
 * An item as the creator's editor sees it — `ItemResponse`.
 *
 * `imageUrl` is an address the client declared, not an upload. There is no
 * media pipeline (docs/architecture.md §13), and the field becomes a reference
 * to a `media` row when there is one; the interface says so rather than
 * pretending to have an uploader.
 */
export interface Item {
  id: string;
  projectId: string;
  name: string;
  description?: string | null;
  imageUrl?: string | null;
  /** Absent for a digital item, which the service refuses to give a weight. */
  weightGrams?: number | null;
  isDigital: boolean;
  sku?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** `CreateItemRequest`. Only the name is required. */
export interface NewItem {
  name: string;
  description?: string | null;
  imageUrl?: string | null;
  weightGrams?: number | null;
  isDigital?: boolean;
  sku?: string | null;
}

/**
 * `ItemPatchRequest`, with JSON Merge Patch semantics.
 *
 * Same rule as `ProjectPatch`: an absent key leaves the field alone, an
 * explicit `null` clears it. That is why every optional field is `T | null`
 * rather than `T`.
 */
export interface ItemPatch {
  name?: string;
  description?: string | null;
  imageUrl?: string | null;
  weightGrams?: number | null;
  isDigital?: boolean;
  sku?: string | null;
}

async function readItem(response: Response): Promise<Item> {
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as Item;
}

/** Every item of the campaign, oldest first. */
export async function listItems(projectId: string, signal?: AbortSignal): Promise<readonly Item[]> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/items`;
  const response = await authorizedFetch(path, { signal });
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as readonly Item[];
}

export async function createItem(
  projectId: string,
  input: NewItem,
  signal?: AbortSignal,
): Promise<Item> {
  return readItem(
    await authorizedFetch(`/v1/projects/${encodeURIComponent(projectId)}/items`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(input),
      signal,
    }),
  );
}

export async function patchItem(id: string, patch: ItemPatch, signal?: AbortSignal): Promise<Item> {
  return readItem(
    await authorizedFetch(`/v1/items/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify(patch),
      signal,
    }),
  );
}

/**
 * Removes an item.
 *
 * `409 ITEM_IN_USE` when a reward tier contains it, carrying the tiers in
 * `meta.rewardTierIds` so the editor can name them. Deleting it would change
 * what a backer was promised, so the refusal is the correct answer and the
 * creator takes it out of each tier first.
 */
export async function deleteItem(id: string, signal?: AbortSignal): Promise<void> {
  const response = await authorizedFetch(`/v1/items/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    signal,
  });
  // 204, so there is nothing to read. Parsing it would fail on an empty body.
  if (!response.ok) throw await errorFrom(response);
}

/* -------------------------------------------------------------------------
 * Reward tiers — docs/architecture.md §10.2 (#32, #34)
 * ---------------------------------------------------------------------- */

/**
 * How a tier reaches the person who backed it — `ShippingType`.
 *
 * A property of the tier, not of the campaign: one campaign routinely offers a
 * digital thank-you, a poster shipped domestically, and a boxed set shipped
 * anywhere, and each is a different question at checkout.
 */
export type ShippingType = 'NONE' | 'DIGITAL' | 'LOCAL_PICKUP' | 'DOMESTIC' | 'INTERNATIONAL';

/** One line of a tier's composition — `RewardItemBody`. */
export interface RewardItemLine {
  itemId: string;
  quantity: number;
}

/**
 * One destination's shipping rate — `ShippingRuleBody`.
 *
 * BOTH AMOUNTS ARE STRINGS, for the reason every amount in this file is
 * (§10.3): they are added to a pledge total and charged to a card. There is no
 * currency on a rate — the whole table is denominated in the campaign's, which
 * the tier already carries, so repeating it on thirty destinations would be
 * thirty chances for one of them to disagree with the tier it belongs to.
 *
 * One type for the request and the response, matching the server's own single
 * record: two would drift the first time a field was added to one of them.
 */
export interface ShippingRate {
  /** ISO 3166-1 alpha-2, uppercase. The service normalises and re-checks it. */
  countryCode: string;
  amount: string;
  /** What each unit after the first costs. `"0.00"` is a flat rate, deliberately. */
  additionalItemAmount: string;
}

/**
 * A reward tier as the creator's editor sees it — `RewardResponse`.
 *
 * The response of every endpoint that touches a tier, so the editor applies the
 * same update whichever call it made. It is the CREATOR's projection and
 * carries what no public response may: the reservation counts and the secret
 * token.
 */
export interface Reward {
  id: string;
  projectId: string;
  title: string;
  description?: string | null;
  price: Money;
  /** An ISO date. A month is what a creator can honestly promise. */
  estimatedDelivery?: string | null;
  /** Null is unlimited, which is the common case. */
  limitQuantity?: number | null;
  /** Places taken by confirmed pledges. Read-only; epic #50 writes it. */
  claimedQuantity: number;
  /** Places held by a checkout in progress. Read-only; #51 writes it. */
  reservedQuantity: number;
  /** Derived from the three above, or null when unlimited. */
  remainingQuantity?: number | null;
  shippingType: ShippingType;
  isEarlyBird: boolean;
  isFeatured: boolean;
  isSecret: boolean;
  /** The link that reaches a secret tier. Present only for the creator. */
  secretToken?: string | null;
  isAddon: boolean;
  sortOrder: number;
  /** ISO 8601, UTC. */
  availableFrom?: string | null;
  /**
   * ISO 8601, UTC. A moment in the PAST is how the service expresses "hidden"
   * (docs/architecture.md §5.3): it withdraws a tier from sale without deleting
   * it, which is the only thing permitted once somebody has backed it. The
   * editor presents that as hiding rather than as a date — see `lib/projects/rewards`.
   */
  availableUntil?: string | null;
  items: readonly RewardItemLine[];
  shippingRules: readonly ShippingRate[];
  /**
   * The optimistic-locking counter.
   *
   * No endpoint takes it yet, so nothing here sends it. It is read because a
   * concurrent write is answered `409 REWARD_MODIFIED`, and knowing the version
   * a refusal was about is what lets a later `If-Match` be added without a
   * second read.
   */
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** `CreateRewardRequest`. A title and a price; the rest is edited afterwards. */
export interface NewReward {
  title: string;
  description?: string | null;
  price: Money;
  estimatedDelivery?: string | null;
  limitQuantity?: number | null;
  shippingType?: ShippingType;
  isEarlyBird?: boolean;
  isFeatured?: boolean;
  isSecret?: boolean;
  isAddon?: boolean;
  availableFrom?: string | null;
  availableUntil?: string | null;
  items?: readonly RewardItemLine[];
}

/**
 * `RewardPatchRequest`, with JSON Merge Patch semantics.
 *
 * `items` replaces the WHOLE composition when present. There is no
 * add-one-line patch, because a composition is what a backer is promised and
 * the client always holds all of it — a per-line patch would let two tabs each
 * remove a different item and leave a tier containing neither.
 *
 * The per-country rates are deliberately absent. They are replaced through
 * `replaceShippingRules`, because a rate table is read as a whole by whatever
 * quotes from it.
 */
export interface RewardPatch {
  title?: string;
  description?: string | null;
  price?: Money;
  estimatedDelivery?: string | null;
  limitQuantity?: number | null;
  shippingType?: ShippingType;
  isEarlyBird?: boolean;
  isFeatured?: boolean;
  isSecret?: boolean;
  isAddon?: boolean;
  availableFrom?: string | null;
  availableUntil?: string | null;
  items?: readonly RewardItemLine[];
}

async function readReward(response: Response): Promise<Reward> {
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as Reward;
}

async function readRewards(response: Response): Promise<readonly Reward[]> {
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as readonly Reward[];
}

/**
 * The campaign's reward list, in display order, INCLUDING secret tiers.
 *
 * A creator who cannot see a secret tier in their own editor cannot edit or
 * withdraw it. What makes it secret is that the public list leaves it out, and
 * that list is a different endpoint owned by the campaign page.
 */
export async function listRewards(
  projectId: string,
  signal?: AbortSignal,
): Promise<readonly Reward[]> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/rewards`;
  return readRewards(await authorizedFetch(path, { signal }));
}

export async function createReward(
  projectId: string,
  input: NewReward,
  signal?: AbortSignal,
): Promise<Reward> {
  return readReward(
    await authorizedFetch(`/v1/projects/${encodeURIComponent(projectId)}/rewards`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(input),
      signal,
    }),
  );
}

export async function patchReward(
  id: string,
  patch: RewardPatch,
  signal?: AbortSignal,
): Promise<Reward> {
  return readReward(
    await authorizedFetch(`/v1/rewards/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify(patch),
      signal,
    }),
  );
}

/**
 * Removes a tier nobody has claimed.
 *
 * `409 REWARD_HAS_BACKERS` once somebody has, with the count in `meta`. §5.3
 * permits no exception and gives the alternative: the tier is withdrawn from
 * sale rather than deleted, so the people who chose it still have a description
 * of what they are owed.
 */
export async function deleteReward(id: string, signal?: AbortSignal): Promise<void> {
  const response = await authorizedFetch(`/v1/rewards/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    signal,
  });
  if (!response.ok) throw await errorFrom(response);
}

/** Copies a tier — wording, price, composition, rates — to the end of the list. */
export async function duplicateReward(id: string, signal?: AbortSignal): Promise<Reward> {
  const path = `/v1/rewards/${encodeURIComponent(id)}/duplicate`;
  return readReward(await authorizedFetch(path, { method: 'POST', signal }));
}

/**
 * Puts the tiers in the order given, and answers the list in that order.
 *
 * EVERY TIER, EXACTLY ONCE, or the service answers `400
 * REWARD_ORDER_INCOMPLETE` naming what was missing and what was unexpected. A
 * partial list would leave the tiers it omits where they were, interleaved with
 * the ones that moved — so the caller sends the whole list, which is the whole
 * list it is already holding.
 */
export async function reorderRewards(
  projectId: string,
  rewardIds: readonly string[],
  signal?: AbortSignal,
): Promise<readonly Reward[]> {
  const path = `/v1/projects/${encodeURIComponent(projectId)}/rewards/reorder`;
  return readRewards(
    await authorizedFetch(path, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify({ rewardIds }),
      signal,
    }),
  );
}

/**
 * Replaces a tier's whole shipping rate table, and answers the whole tier.
 *
 * `PUT` because it is a replacement: a country the body leaves out is a country
 * the creator has removed, and an empty list clears the table. Merging would
 * leave a creator shipping somewhere they believe they no longer do, which is
 * discovered by a backer selecting it.
 *
 * Refused with `REWARD_FIELD_INVALID` on `shippingType` when the tier is not
 * shipped, because rates on a tier nothing quotes from are rates a creator
 * believes are in force.
 */
export async function replaceShippingRules(
  id: string,
  rules: readonly ShippingRate[],
  signal?: AbortSignal,
): Promise<Reward> {
  return readReward(
    await authorizedFetch(`/v1/rewards/${encodeURIComponent(id)}/shipping-rules`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify({ rules }),
      signal,
    }),
  );
}

/* -------------------------------------------------------------------------
 * Still to come. Each of these is a function in THIS module, not a new client.
 *
 *   #37 checklist                GET /v1/projects/{id}/checklist
 *   #39 pre-launch and follows   POST/DELETE /v1/projects/{id}/prelaunch
 *   #31 submit, launch, cancel   POST /v1/projects/{id}/submit | launch | cancel
 *
 * The story, risks, and cover fields of `ProjectPatch` are already here because
 * they are written through the same autosave path; the tabs that own them add
 * their own endpoints, not their own patch type.
 * ---------------------------------------------------------------------- */
