/**
 * The service answers failures with RFC 9457 problem details, on
 * `application/problem+json`. Some failures do not reach a handler at all —
 * Spring Security refuses an unauthenticated request in the filter chain, and
 * the session endpoints return a deliberately bare 404 — so a body is never
 * assumed to exist.
 */
export interface Problem {
  type?: string;
  title?: string;
  detail?: string;
  status?: number;
  /** Field name to message, on a validation failure. */
  errors?: Record<string, string>;
  /** Mirrors the `Retry-After` header on a 429. */
  retryAfterSeconds?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null = null, message?: string) {
    super(
      message ?? problem?.detail ?? problem?.title ?? `The request failed with status ${status}.`,
    );
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

/** Reads the problem body, or returns null when there is nothing to read. */
export async function problemOf(response: Response): Promise<Problem | null> {
  if (!(response.headers.get('content-type') ?? '').includes('json')) return null;

  try {
    return (await response.json()) as Problem;
  } catch {
    // A truncated or empty body is not worth failing over — the status code
    // already carries the part the caller acts on.
    return null;
  }
}

export async function errorFrom(response: Response): Promise<ApiError> {
  return new ApiError(response.status, await problemOf(response));
}
