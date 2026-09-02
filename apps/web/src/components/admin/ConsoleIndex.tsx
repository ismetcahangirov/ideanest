import { Link } from '../../i18n/navigation';
import type { ConsoleIndexCopy } from '../../lib/i18n/admin-copy';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import {
  CONSOLE_MODULES,
  completeModuleCount,
  partialModuleCount,
  type ConsoleModule,
  type ModuleState,
} from '../../lib/admin/navigation';

/**
 * §4.11's sixteen modules, and the state of each — issue #294.
 *
 * <h2>The page is the epic's definition of done, rendered</h2>
 *
 * #259 requires that "every module in §4.11's table has either a screen or an open blocker
 * naming what it needs". A rail listing the nine that work would meet that on paper and lose
 * it in practice: a console with nine entries reads as a console that is nine screens, and
 * the seven missing modules become something somebody discovers by asking. This is the page
 * that answers the question before it is asked.
 *
 * <h2>A blocked module is not a link, and does not look like one</h2>
 *
 * There is no href, no hover treatment and no cursor change — the row is text with a reason
 * under it. A disabled link is worse than no link: it is still in the tab order in some
 * browsers, it still reads as a destination to a screen reader, and it teaches people to
 * click things that do nothing.
 *
 * <h2>The state is never colour alone</h2>
 *
 * docs/ui-kit.md §9.2. Each row carries a `Tag` whose text says which of the three states
 * it is in, so the distinction survives a monochrome screen and a screen reader. The colour
 * is a second signal and never the only one — and <strong>nothing here is lime</strong>:
 * `--lime-500` means "act now" (docs/ui-kit.md), and a module that is blocked on somebody
 * else's issue is the opposite of something to act on.
 *
 * <h2>Static, and therefore free — and it imports nothing from the kit</h2>
 *
 * Nothing on this page fetches. It is a server component over a frozen list, so it costs the
 * browser no JavaScript at all beyond what the shell already carries — which is the right
 * shape for the one console screen that is read most often and acted on least.
 *
 * <p><strong>Which is why the state label is markup rather than the kit's `Tag`.</strong>
 * `@ideanest/ui`'s root barrel re-exports `Table`, `Field`, `Radio` and `Combobox`, and every
 * one of them calls `createContext` — so a server component that imports <em>anything</em>
 * from that barrel does not merely pay for it, it fails to build. `MinimalShell` records the
 * measurement of the same mechanism from the other side: a barrel in a `transpilePackages`
 * source package lands in one shared chunk, and it cost this application 83.3 KiB on every
 * route the last time somebody imported it somewhere it did not belong.
 *
 * <p>The three spans below are `Tag`'s own variant classes, copied deliberately and not
 * abstracted: three class strings in one file is a smaller thing to keep in step than a
 * second component in the kit whose only purpose is to be importable from a server.
 */

/*
 * WHAT A READER IS TOLD THE STATE IS lives in `admin.states` since #324. Words, because
 * colour is not enough (docs/ui-kit.md §9.2) — the tone below sits beside the word and never
 * instead of it.
 */

/**
 * The second signal, never the only one.
 *
 * `default` for a blocked module rather than `danger`: nothing is wrong with AD-06 — it is
 * waiting on a refund endpoint that has not been written, which is a fact about the schedule
 * and not a fault. Red on seven of sixteen rows would make the page read as an incident.
 */
const STATE_TONES: Readonly<Record<ModuleState, string>> = {
  built: 'bg-success/12 text-success',
  partial: 'bg-warning/12 text-warning',
  blocked: 'bg-surface-3 text-white/64',
};

/** `Tag`'s shape, without the barrel. See the docblock on why this page cannot import it. */
const TAG = 'inline-flex h-[26px] items-center gap-1 rounded-sm px-2.5 text-xs font-medium';

function issueHref(issue: number): string {
  return `https://github.com/ismetcahangirov/ideanest/issues/${issue}`;
}

function ModuleRow({
  module,
  copy,
}: {
  readonly module: ConsoleModule;
  readonly copy: ConsoleIndexCopy;
}) {
  const words = copy.modules[module.code];
  const heading = (
    <span className="text-[15px] font-medium text-white">
      <span className="mr-2 font-mono text-xs tracking-[0.04em] text-white/40">{module.code}</span>
      {words?.title}
    </span>
  );

  return (
    <li className="rounded-xl border border-white/8 bg-surface-1 p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        {module.href === null ? (
          heading
        ) : (
          <Link
            href={module.href}
            className="rounded-lg transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            {heading}
          </Link>
        )}
        <span className={`${TAG} ${STATE_TONES[module.state]}`}>
          {copy.states[module.state]}
        </span>
      </div>

      <p className="mt-2 max-w-[68ch] text-sm text-white/64">{words?.summary}</p>

      {words?.waitingOn === undefined ? null : (
        <p className="mt-2 max-w-[68ch] text-sm text-white/48">
          {words.waitingOn}{' '}
          <a
            href={issueHref(module.issue)}
            className="text-white/64 underline underline-offset-2 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            {fillPlaceholders(copy.issue, { issue: String(module.issue) })}
          </a>
        </p>
      )}
    </li>
  );
}

export interface ConsoleIndexProps {
  /** The page's words, resolved by the route — see `lib/i18n/admin-copy.ts`. */
  readonly copy: ConsoleIndexCopy;
}

export function ConsoleIndex({ copy }: ConsoleIndexProps) {
  /*
   * #405: this sentence used to say how many modules have a screen and then promise that
   * "the rest say what they are waiting for". All sixteen have a screen, so there was no
   * rest and the second clause described nothing — while nine of the sixteen are partly
   * built and do carry a waiting-on note, which is the fact it was reaching for. It says
   * how many are finished and how many are not.
   */
  const complete = completeModuleCount();
  const partial = partialModuleCount();

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        {copy.title}
      </h1>
      <p className="mt-2 max-w-[68ch] text-sm text-white/64">
        {fillPlaceholders(copy.standfirst, {
          total: String(CONSOLE_MODULES.length),
          complete: String(complete),
          partial: String(partial),
        })}
      </p>

      <ul className="mt-8 flex list-none flex-col gap-3">
        {CONSOLE_MODULES.map((module) => (
          <ModuleRow key={module.code} module={module} copy={copy} />
        ))}
      </ul>

      <p className="mt-8 max-w-[68ch] text-sm text-white/48">{copy.footnote}</p>
    </div>
  );
}
