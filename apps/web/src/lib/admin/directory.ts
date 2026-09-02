import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * What the console's identifiers are called — issue #402.
 *
 * <h2>The screen this exists because of</h2>
 *
 * `/admin/staff` told the person reading it that they were signed in as `c5c5493d`, on a
 * session whose site header renders their name. The payout file paid `18844dbc`, the audit
 * trail recorded that `4ae450ba` suspended somebody, and the two-person payout signature
 * list — whose entire purpose is *which two people* — named neither of them. Every one of
 * those is an eight-character fragment of a UUID, and none of the endpoints behind those
 * screens returns a name.
 *
 * <p>`GET /v1/admin/directory` is the endpoint that ends that. It takes identifiers and
 * answers with display names and public paths, needs no capability beyond working here, and
 * is the one console read that is not audited — the service's own module comment carries the
 * argument for all three.
 *
 * <h2>What this module is not</h2>
 *
 * <strong>It is not a search.</strong> Finding an account by name or address is
 * `GET /v1/admin/users?query=`, which is audited and hands over email addresses, and
 * `AccountPicker` is what uses it. This resolves identifiers a screen is already holding,
 * which is a different question with a much lower cost of being answered.
 *
 * <strong>It does not decide what a missing name looks like.</strong> An identifier with
 * nothing behind it is absent from the answer rather than mapped to null — §17.4 leaves rows
 * whose author has been anonymised — and every caller renders `shortId` for those, exactly
 * as the whole console did before this existed.
 */

/** A person, named. No email address: see the service's comment on why there is none. */
export interface NamedAccount {
  id: string;
  /** The display name, as they last saved it. Never blank. */
  name: string;
  /** Their half of a public profile path, which is what makes the name a link. */
  slug: string;
}

/** A campaign, in whatever state it is in. */
export interface NamedProject {
  id: string;
  title: string;
  /**
   * Absent together with {@link creatorSlug} when the campaign has no public path.
   *
   * A campaign in review has none, and half a path is a link to no route at all — which is
   * the 404 #399 is about. A caller with neither renders the title without a link.
   */
  slug?: string | null;
  creatorSlug?: string | null;
  /** Whose campaign it is, so a row holding a campaign can name its creator from one answer. */
  creatorId?: string | null;
}

export interface ConsoleDirectory {
  accounts: NamedAccount[];
  projects: NamedProject[];
}

/**
 * The service's own ceiling, mirrored so the client can split rather than be refused.
 *
 * Counted across both lists together, because the constraint behind the number is the
 * request: a UUID costs forty-five characters as a query parameter, and two full lists
 * would put the query over the eight-kilobyte header block a server accepts — which is
 * refused with an HTML error page rather than with a code anything here could branch on.
 *
 * Over it the service answers `TOO_MANY_IDENTIFIERS` rather than truncating, because a
 * screen answered about fewer rows than it asked about renders the remainder as bare
 * identifiers with no name and no reason. No console page holds anything like this many
 * distinct references; the batching in {@link lookUpNames} exists so that the bound stays
 * the service's business and never a screen's.
 */
export const MAX_IDENTIFIERS = 100;

/** One lookup. Empty lists are an empty answer and no request. */
export async function readDirectory(
  accountIds: readonly string[],
  projectIds: readonly string[],
  signal?: AbortSignal,
): Promise<ConsoleDirectory> {
  if (accountIds.length === 0 && projectIds.length === 0) {
    return { accounts: [], projects: [] };
  }

  const params = new URLSearchParams();
  // Repeated rather than comma-joined: `?account=a,b` is one identifier whose text contains
  // a comma, and Spring binds the repeated form to a list without a converter.
  for (const id of accountIds) params.append('account', id);
  for (const id of projectIds) params.append('project', id);

  const response = await authorizedFetch(`/v1/admin/directory?${params.toString()}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as ConsoleDirectory;
}

/**
 * Names for however many identifiers a screen is holding, in as few requests as that takes.
 *
 * <p>Splitting here rather than at the call site because the ceiling is the service's and a
 * screen should not have to know it. Batches are requested in sequence rather than together:
 * a console page needs one batch in practice, the second exists so that an unusually long
 * page still renders names, and firing an unbounded number of parallel requests to be quick
 * about a case that does not happen is the wrong trade.
 */
export async function lookUpNames(
  accountIds: readonly string[],
  projectIds: readonly string[],
  signal?: AbortSignal,
): Promise<ConsoleDirectory> {
  const accounts: NamedAccount[] = [];
  const projects: NamedProject[] = [];

  // The ceiling counts both lists together, so the accounts are drained first and the
  // campaigns fill whatever each request has left. A batch is never empty while anything
  // is outstanding, which is what makes the loop terminate.
  let people = 0;
  let campaigns = 0;
  while (people < accountIds.length || campaigns < projectIds.length) {
    const takeAccounts = Math.min(MAX_IDENTIFIERS, accountIds.length - people);
    const takeProjects = Math.min(MAX_IDENTIFIERS - takeAccounts, projectIds.length - campaigns);

    const page = await readDirectory(
      accountIds.slice(people, people + takeAccounts),
      projectIds.slice(campaigns, campaigns + takeProjects),
      signal,
    );
    accounts.push(...page.accounts);
    projects.push(...page.projects);

    people += takeAccounts;
    campaigns += takeProjects;
  }

  return { accounts, projects };
}

/** What a screen holds after a lookup: a name per identifier, and nothing for the rest. */
export interface DirectoryNames {
  readonly accounts: ReadonlyMap<string, NamedAccount>;
  readonly projects: ReadonlyMap<string, NamedProject>;
}

/** Nothing resolved. What every screen renders with until its lookup answers. */
export const NO_NAMES: DirectoryNames = Object.freeze({
  accounts: new Map<string, NamedAccount>(),
  projects: new Map<string, NamedProject>(),
});

/** The answer, keyed for rendering. */
export function namesFrom(directory: ConsoleDirectory): DirectoryNames {
  return {
    accounts: new Map(directory.accounts.map((account) => [account.id, account])),
    projects: new Map(directory.projects.map((project) => [project.id, project])),
  };
}

/**
 * The identifiers worth asking about, from whatever a screen scraped out of its rows.
 *
 * <p>Nulls, blanks and duplicates removed — a queue of twenty payouts to one creator asks
 * about that creator once — and the order is the order they were first seen, so a request is
 * reproducible in a log.
 */
export function identifiersIn(values: readonly (string | null | undefined)[]): string[] {
  const seen = new Set<string>();
  for (const value of values) {
    if (value != null && value !== '') seen.add(value);
  }
  return [...seen];
}
