'use client';

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../lib/api/problem';
import { getProjectEdit, type ProjectEdit } from '../../lib/projects/api';

/**
 * Loads the creator's projection of one project.
 *
 * Every editor tab needs it — the title in the header, the state badge, and
 * `lockedFields` — so the load lives here rather than in each tab. The tab that
 * owns a field owns the writing of it; this only reads.
 *
 * `apply` exists because every mutation in contract §5 answers with the whole
 * `ProjectEdit`. Taking the server's answer as the new truth is cheaper and
 * more honest than patching a local copy and hoping the two agree.
 */
export type ProjectLoadStatus = 'loading' | 'ready' | 'failed' | 'signed-out';

export interface ProjectEditHandle {
  project: ProjectEdit | null;
  status: ProjectLoadStatus;
  error: string | null;
  reload: () => void;
  apply: (project: ProjectEdit) => void;
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return 'You do not have access to this project.';
    if (cause.status === 404) {
      // 404 covers "no such project" and "not yours", deliberately
      // indistinguishable so the endpoint cannot be used to enumerate ids.
      return 'That project could not be found.';
    }
    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

export function useProjectEdit(projectId: string): ProjectEditHandle {
  const [project, setProject] = useState<ProjectEdit | null>(null);
  const [status, setStatus] = useState<ProjectLoadStatus>('loading');
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      setStatus('loading');
      try {
        const loaded = await getProjectEdit(projectId, controller.signal);
        if (controller.signal.aborted) return;

        setProject(loaded);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        if (cause instanceof ApiError && cause.status === 401) {
          setStatus('signed-out');
          return;
        }
        setError(messageFor(cause));
        setStatus('failed');
      }
    })();

    return () => controller.abort();
  }, [projectId, attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const apply = useCallback((saved: ProjectEdit) => setProject(saved), []);

  return { project, status, error, reload, apply };
}
