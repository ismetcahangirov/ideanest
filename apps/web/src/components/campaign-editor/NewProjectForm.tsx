'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { CharacterCount, Field, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import { createProject } from '../../lib/projects/api';
import { TITLE_MAX_CHARACTERS, characterCount } from '../../lib/projects/basics';

/**
 * Starting a campaign: one field, then the editor.
 *
 * IT ASKS BEFORE IT CREATES. The obvious alternative — POST on mount and
 * redirect — creates a draft for anybody who so much as opens the page,
 * including twice for the same visit under React's development double-effect,
 * and leaves the creator's dashboard filling up with empty projects they never
 * asked for. `POST /v1/projects` takes a title (contract §5), so asking for the
 * title is both the smallest possible form and the thing that makes the request
 * deliberate.
 *
 * Everything else about the project is edited afterwards, with autosave. This is
 * the only place in the editor with a button that submits.
 */
function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 401) return 'Sign in to start a project.';
    if (cause.status === 403) return 'This account is not allowed to create projects.';
    return (
      cause.problem?.detail ??
      cause.problem?.title ??
      'The draft could not be created. Try again.'
    );
  }
  return 'The service could not be reached, so no draft was created. Try again.';
}

export function NewProjectForm() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const trimmed = title.trim();
  const length = characterCount(trimmed);
  const tooLong = length > TITLE_MAX_CHARACTERS;

  const error = tooLong
    ? `A title is ${TITLE_MAX_CHARACTERS} characters or fewer. Remove ${length - TITLE_MAX_CHARACTERS}.`
    : undefined;

  async function create(): Promise<void> {
    if (trimmed === '' || tooLong || creating) return;

    setCreating(true);
    setFailure(null);
    try {
      const project = await createProject({ title: trimmed });

      /*
       * `replace`, not `push`. Going back to this page after the draft exists
       * would offer to create a second one, which is never what the back button
       * was for.
       */
      router.replace(`/projects/${encodeURIComponent(project.id)}/edit/basics`);
    } catch (cause) {
      setFailure(messageFor(cause));
      setCreating(false);
    }
  }

  return (
    <form
      className="mt-8 flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        void create();
      }}
    >
      {failure !== null && (
        <InlineAlert variant="danger" title="The draft was not created">
          {failure}
        </InlineAlert>
      )}

      <Field
        label="Project title"
        required
        hint={`It can be changed at any time before the campaign launches. ${TITLE_MAX_CHARACTERS} characters or fewer.`}
        error={error}
      >
        <TextInput
          value={title}
          autoComplete="off"
          disabled={creating}
          onChange={(event) => setTitle(event.target.value)}
        />
        <CharacterCount count={characterCount(title)} limit={TITLE_MAX_CHARACTERS} />
      </Field>

      {/*
        White, not lime. Lime means urgent (docs/ui-kit.md §2.3), and starting a
        project is not urgent — it is simply the primary action on the page.
      */}
      <div>
        <Pill type="submit" disabled={trimmed === '' || tooLong || creating}>
          {creating ? 'Creating the draft' : 'Start editing'}
        </Pill>
      </div>
    </form>
  );
}
