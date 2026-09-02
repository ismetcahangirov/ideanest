'use client';

import { InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { ageInSeconds, readHealth, type HealthStatus } from '../../lib/admin/health';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import type { HealthDashboardCopy } from '../../lib/i18n/admin/platform-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

/**
 * §4.11's AD-16: queue depth, failed jobs, provider status — §18, issue #316.
 *
 * <h2>It says it is not monitoring, and that is load-bearing</h2>
 *
 * #316 was labelled blocked on #138, and it was not quite: every number here is a count the
 * service can already take, so the page needs no collector and no exporter. What it does not do
 * is alert — nobody is woken by anything on this screen — and a dashboard presented as though
 * it were monitoring is worse than an honest gap, because the gap then looks filled.
 *
 * The banner comes from the service&apos;s `monitored` field rather than being hard-coded, so
 * the day #138 lands it disappears without a change here.
 *
 * <h2>Waiting and dead are never added together</h2>
 *
 * A deep queue is a platform under load and will drain. A dead row is a platform that has given
 * up and will not. One number would let a thousand-item backlog hide the one message that will
 * never be sent, so any dead row is critical regardless of depth.
 *
 * <h2>Not configured is not unhealthy</h2>
 *
 * §9.3 asks for at least two payment integrations and a deployment may run one. Painting the
 * others red would put permanent failures on a screen whose whole job is to show the ones that
 * are not permanent.
 *
 * <h2>The measurement's age is two sentences rather than a number and a unit</h2>
 *
 * `lib/i18n/admin/platform-copy.ts` records why: a unit concatenated onto a figure is a
 * sentence no translation can reorder, and this screen used to build four of them that way.
 *
 * <h2>"Late by zero minutes" cannot be said any more — issue #405</h2>
 *
 * <p>Ten of the nineteen jobs rendered as amber with the detail "READY · 0 minutes late",
 * beside two that were six and nine thousand minutes behind and were the same colour. The
 * severity was the service's — `SystemHealthService` now has a threshold and the argument
 * for it — and the sentence was this file's: it printed the overdue line for anything at all
 * past due and then floored the seconds to minutes, so a job one second late was reported as
 * late by none.
 *
 * <p>Both halves are the same threshold now: under a minute is on time, in words as well as
 * in colour.
 *
 * <h2>The queues are named in the reader's language — issue #405</h2>
 *
 * <p>They used to be the service's own words. So `/admin/health` listed "Outbox" and
 * "Scheduled jobs" under the Azerbaijani heading "Növbələr", directly above a section headed
 * "Planlaşdırılmış işlər" — the Azerbaijani for the second of them. One concept, two
 * languages, one screen. `QueueDepthSource.queueName()` answers an identifier now and the
 * label is looked up here, with the identifier as the fallback so a queue this catalogue has
 * not been taught about renders as itself rather than as nothing.
 */

/**
 * Below this, a job is not late — the same minute {@code ideanest.platform.health.late-job-after}
 * defaults to.
 *
 * <p>Duplicated rather than sent, deliberately: it decides a sentence here and a severity
 * there, and the two agreeing is what #405 is about. A deployment that widens the service's
 * threshold makes this screen say "on time" for a shorter range than it grades healthy, which
 * is the harmless direction — the tag would read amber beside "on time", where the reverse
 * would read healthy beside "late".
 */
const LATE_AFTER_SECONDS = 60;
export interface HealthDashboardProps {
  readonly copy: HealthDashboardCopy;
}

export function HealthDashboard({ copy }: HealthDashboardProps) {
  const health = useConsoleResource(
    (signal) => readHealth(signal),
    copy.subject,
    copy.refusals,
    [],
  );

  if (health.status === 'signed-out' || health.status === 'forbidden') {
    return <ConsoleRefusal status={health.status} capability={health.capability} subject={copy.subject} copy={copy.refusals} />;
  }

  if (health.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingList}>
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
              <Skeleton height="1rem" width="35%" />
              <Skeleton height="0.875rem" width="60%" className="mt-3" />
            </div>
          ))}
        </div>
      </SkeletonGroup>
    );
  }

  if (health.status === 'failed' || health.data === null) {
    return (
      <>
        <InlineAlert variant="danger" title={copy.errorTitle}>
          {health.error ?? copy.readFailed}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={health.reload}>
          {copy.tryAgain}
        </Pill>
      </>
    );
  }

  const snapshot = health.data;
  const age = ageInSeconds(snapshot);

  return (
    <div className="flex flex-col gap-8">
      {!snapshot.monitored && (
        <InlineAlert variant="info" title={copy.notMonitoredTitle}>
          {copy.notMonitoredBody}
        </InlineAlert>
      )}

      <section aria-labelledby="overall-heading">
        <h2 id="overall-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.overall} <StatusTag status={snapshot.status} copy={copy} />
        </h2>
        <p className="mt-2 text-xs text-white/48">
          {age < 60
            ? fillPlaceholders(copy.measuredSeconds, { count: String(age) })
            : fillPlaceholders(copy.measuredMinutes, { count: String(Math.floor(age / 60)) })}{' '}
          <button
            type="button"
            onClick={health.reload}
            className="rounded text-white/64 underline transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            {copy.measureAgain}
          </button>
        </p>
      </section>

      <section aria-labelledby="queues-heading">
        <h2 id="queues-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.queues}
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.queues.map((queue) => (
            <li
              key={queue.name}
              className="flex flex-wrap items-baseline justify-between gap-2 rounded-lg border border-white/8 bg-surface-1 p-4"
            >
              {/* The identifier is the fallback, so a new queue renders as itself. */}
              <p className="text-sm text-white" title={queue.name}>
                {copy.queue[queue.name] ?? queue.name}
              </p>
              <p className="text-sm text-white/64">
                {fillPlaceholders(copy.waiting, { count: String(queue.waiting) })}
                {queue.dead > 0
                  ? ` · ${fillPlaceholders(copy.givenUp, { count: String(queue.dead) })}`
                  : ''}{' '}
                <StatusTag status={queue.status} copy={copy} />
              </p>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="jobs-heading">
        <h2 id="jobs-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.jobs}
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.jobs.map((job) => (
            <li key={job.name} className="rounded-lg border border-white/8 bg-surface-1 p-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-mono text-sm text-white">{job.name}</p>
                <StatusTag status={job.status} copy={copy} />
              </div>
              <p className="mt-2 text-xs text-white/48">
                {/* The wire word is on the `title` and the sentence is on the screen — the
                    reverse of what this did, and #403's argument for the change: `READY` on
                    nineteen rows is not information in any language, and the value somebody
                    greps for is still one hover away. A state with no sentence is drawn as
                    itself. */}
                <span title={job.state}>{copy.jobState[job.state] ?? job.state}</span>
                {/*
                  #405: the threshold and not "anything past due". A job picked up a second
                  after it fell due was reported as "late by 0 minutes", which is a sentence
                  that contradicts itself and was drawn beside an amber tag.
                */}
                {job.overdueBySeconds >= LATE_AFTER_SECONDS
                  ? ` · ${fillPlaceholders(copy.overdue, {
                      count: String(Math.floor(job.overdueBySeconds / 60)),
                    })}`
                  : ` · ${copy.onTime}`}
                {job.attempts > 0
                  ? ` · ${fillPlaceholders(copy.attempts, { count: String(job.attempts) })}`
                  : ''}
              </p>
              {job.lastError && (
                <p className="mt-2 break-words text-xs text-white/64">{job.lastError}</p>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="providers-heading">
        <h2 id="providers-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.providers}
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.providers.map((provider) => (
            <li
              key={`${provider.kind}:${provider.provider}`}
              className="rounded-lg border border-white/8 bg-surface-1 p-4"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="text-sm text-white">
                  {provider.provider}
                  <span className="ml-2 text-white/40">{provider.kind}</span>
                </p>
                {provider.configured ? (
                  <StatusTag status={provider.status} copy={copy} />
                ) : (
                  <Tag>{copy.notConfigured}</Tag>
                )}
              </div>
              {provider.detail && <p className="mt-2 text-xs text-white/48">{provider.detail}</p>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

/**
 * The status, as a word rather than only a colour.
 *
 * CLAUDE.md: colour alone must never carry meaning. A dashboard is exactly where that rule
 * is most often broken and most expensive to break, because the whole page is a colour.
 */
function StatusTag({
  status,
  copy,
}: {
  readonly status: HealthStatus;
  readonly copy: HealthDashboardCopy;
}) {
  return <Tag>{copy.status[status] ?? status}</Tag>;
}
