import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-12: what is switched on, for whom — issue #312.
 *
 * <h2>Off means off, including for the named accounts</h2>
 *
 * `enabled` is a kill switch. An account on the explicit list does not see a disabled flag,
 * because "I turned it off and it is still on for some people" is the worst possible
 * property of the control somebody reaches for during an incident. The screen says so
 * beside the switch.
 *
 * <h2>A percentage is a stable hash, not a sample</h2>
 *
 * Which accounts fall inside a rollout is decided by hashing the flag and the account, so
 * widening a rollout only ever adds people. A stored sample would have to be recomputed
 * when the percentage moved, and every recomputation takes the feature away from somebody
 * who had it.
 */

export interface FeatureFlag {
  key: string;
  description: string;
  /** The kill switch. Off is off for everybody. */
  enabled: boolean;
  rolloutPercentage: number;
  /** Always in, whatever the percentage says — as long as the flag is enabled. */
  enabledAccounts: string[];
  updatedAt: string;
  updatedBy: string;
}

export interface FeatureFlagList {
  flags: FeatureFlag[];
}

/** Every flag, alphabetically. */
export async function readFlags(signal?: AbortSignal): Promise<FeatureFlagList> {
  const response = await authorizedFetch('/v1/admin/feature-flags', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as FeatureFlagList;
}

export interface SaveFlagRequest {
  readonly key: string;
  readonly description: string;
  readonly enabled: boolean;
  readonly rolloutPercentage: number;
  readonly enabledAccounts: string[];
  readonly signal?: AbortSignal;
}

/**
 * Creates the flag at this name, or replaces everything about it except the name.
 *
 * One verb for both, because the key is the identity and a create-versus-update distinction
 * would make the console choose from a list it may have loaded a minute ago — and choose
 * wrong exactly when two people are editing.
 */
export async function saveFlag(request: SaveFlagRequest): Promise<FeatureFlag> {
  const response = await authorizedFetch(
    `/v1/admin/feature-flags/${encodeURIComponent(request.key)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        description: request.description,
        enabled: request.enabled,
        rolloutPercentage: request.rolloutPercentage,
        enabledAccounts: request.enabledAccounts,
      }),
      signal: request.signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as FeatureFlag;
}

/**
 * What the service accepts as a flag name.
 *
 * Mirrors V50's `CHECK`, so the screen can refuse before the request rather than surfacing
 * a constraint violation. Lower case, hyphenated, three characters or more.
 */
export const FLAG_KEY_PATTERN = /^[a-z][a-z0-9-]{1,62}[a-z0-9]$/;
