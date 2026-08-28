/**
 * Every word the administration console's frame draws — issue #324.
 *
 * <h2>The decision this file records</h2>
 *
 * `lib/admin/navigation.ts` used to argue that the console should stay English, on the
 * grounds that §21.1's catalogue is for the product's readers while the console's readers
 * are the few people who operate the platform. That is reversed: the platform is to be
 * legible in all four languages to everybody who uses it, staff included. A moderator who
 * reads Azerbaijani is not a different class of reader from a backer who does.
 *
 * <h2>What this covers, and what it deliberately does not</h2>
 *
 * The frame: the bar, the rail, and the index that lists §4.11's sixteen modules with what
 * each is waiting for. **The twenty-six screens inside it are still English literals**, and
 * `apps/web/README.md` records that rather than leaving it to be discovered — including
 * `ConsoleRefusal`, whose sentence names the thing a screen was going to show
 * ("…to read the audit trail") and therefore cannot be translated until the screen that
 * supplies the noun is.
 *
 * <h2>Keyed by the identifiers rather than by a name of its own</h2>
 *
 * A module's words are looked up by its §4.11 code and a rail entry's by its href. Both are
 * already the identifier the specification, the issues and the router agree on, so there is
 * no third name to keep in step — and a destination added to the rail without a label is a
 * missing key rather than a silently English one.
 */
export interface AdminTranslator {
  (key: string): string;
  raw(key: string): unknown;
}

/** One module's words, as `admin.modules.{code}` holds them. */
export interface ModuleCopy {
  readonly title: string;
  readonly summary: string;
  /** Present exactly when the module's state is not `built`. */
  readonly waitingOn?: string;
}

export interface AdminShellCopy {
  /** The word beside the wordmark. The brand itself is never translated. */
  readonly console: string;
  readonly backToSite: string;
  readonly navLabel: string;
  /** Keyed by `ConsoleGroup.heading`. */
  readonly groups: Readonly<Record<string, string>>;
  /** Keyed by href, which is what a rail entry is. */
  readonly links: Readonly<Record<string, string>>;
}

export interface ConsoleIndexCopy {
  readonly title: string;
  /** Carries `{total}` and `{built}`. */
  readonly standfirst: string;
  /** Carries `{issue}`. */
  readonly issue: string;
  readonly footnote: string;
  /** Keyed by `ModuleState`. Never the only carrier of the state — a tone sits beside it. */
  readonly states: Readonly<Record<string, string>>;
  /** Keyed by §4.11's module code. */
  readonly modules: Readonly<Record<string, ModuleCopy>>;
}

export function adminShellCopyFrom(t: AdminTranslator): AdminShellCopy {
  return {
    console: t('console'),
    backToSite: t('backToSite'),
    navLabel: t('navLabel'),
    groups: t.raw('groups') as Readonly<Record<string, string>>,
    links: t.raw('links') as Readonly<Record<string, string>>,
  };
}

export function consoleIndexCopyFrom(t: AdminTranslator): ConsoleIndexCopy {
  return {
    title: t('index.title'),
    standfirst: String(t.raw('index.standfirst')),
    issue: String(t.raw('index.issue')),
    footnote: t('index.footnote'),
    states: t.raw('states') as Readonly<Record<string, string>>,
    modules: t.raw('modules') as Readonly<Record<string, ModuleCopy>>,
  };
}
