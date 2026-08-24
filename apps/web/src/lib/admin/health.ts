import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-16: queue depth, failed jobs, provider status — §18, issue #316.
 *
 * <h2>This is not monitoring, and the screen says so</h2>
 *
 * §18's observability work is a system that watches continuously and wakes somebody. This
 * is a page a member of staff opens when a creator says their update never arrived. Every
 * number on it is a count the service can already take, which is why it did not need #138 —
 * but nothing here alerts, and presenting a dashboard as though it were monitoring would be
 * worse than an honest gap, because the gap would then look filled.
 *
 * `monitored` comes from the service for exactly that reason: when #138 lands it becomes
 * true and the disclaimer disappears with no change here.
 */

/** Three levels. Worst-wins when they are rolled up, because an average of one broken queue is useless. */
export type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'CRITICAL';

export interface QueueHealth {
  name: string;
  waiting: number;
  /**
   * Rows that ran out of attempts.
   *
   * Never added into `waiting`: a deep queue is a platform under load and a dead row is a
   * platform that has given up, and one number would let a thousand-item backlog hide one
   * message that will never be sent.
   */
  dead: number;
  status: HealthStatus;
}

export interface JobHealth {
  name: string;
  state: string;
  lastRunAt?: string | null;
  nextAttemptAt?: string | null;
  /** Zero when it is not due yet. Seconds, so the column sorts as a number. */
  overdueBySeconds: number;
  attempts: number;
  lastError?: string | null;
  status: HealthStatus;
}

export interface ProviderHealth {
  /** What the screen groups by — "Payments". A mail relay would arrive under its own heading. */
  kind: string;
  provider: string;
  /** Not configured is not unhealthy. It is switched off, which is a different column. */
  configured: boolean;
  available: boolean;
  detail?: string | null;
  status: HealthStatus;
}

export interface PlatformHealth {
  /** When this was measured. A stale tab is not a healthy platform. */
  at: string;
  status: HealthStatus;
  /** False until #138. See the module comment. */
  monitored: boolean;
  queues: QueueHealth[];
  jobs: JobHealth[];
  providers: ProviderHealth[];
}

/** The whole screen, as of now. */
export async function readHealth(signal?: AbortSignal): Promise<PlatformHealth> {
  const response = await authorizedFetch('/v1/admin/health', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PlatformHealth;
}

/** How long ago the snapshot was taken, in seconds. */
export function ageInSeconds(health: PlatformHealth, now: Date = new Date()): number {
  return Math.max(0, Math.round((now.getTime() - new Date(health.at).getTime()) / 1000));
}
