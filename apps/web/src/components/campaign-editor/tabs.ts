/**
 * The editor's sections, in the order a campaign is built.
 *
 * ONE LIST, and it is the only place a tab is declared. The shell renders it,
 * every tab page names itself from it, and the routes are derived from it — so
 * adding a section is a row here plus the route it points at, not a hunt through
 * the navigation markup.
 *
 * `available: false` means the route does not exist yet. It renders as a
 * disabled tab rather than as a stub page, and that is a deliberate choice
 * between two bad options:
 *
 *   A stub page lies. A creator who lands on an empty "Rewards" screen cannot
 *   tell whether the feature is broken, empty, or unbuilt, and the honest answer
 *   — "this is not finished yet" — is exactly what a disabled tab says.
 *
 *   A stub page also has to exist as a file. `apps/web/src/app/projects/[id]/
 *   edit/rewards/page.tsx` belongs to #34, and creating it here to be deleted
 *   there is a merge conflict in a file this issue does not own.
 *
 * The tab stays focusable while disabled: a keyboard user is told the section
 * exists and is not yet available, which is more useful than a control that
 * cannot be reached at all.
 */
export type EditorTabKey = 'basics' | 'rewards' | 'story' | 'prelaunch' | 'review';

export interface EditorTab {
  key: EditorTabKey;
  label: string;
  /** Path segment under `/projects/[id]/edit`. */
  segment: EditorTabKey;
  /** Whether the route exists. Flip it in the pull request that adds the page. */
  available: boolean;
  /** The sub-issue that builds it. Shown to nobody; it is here for the reader. */
  issue: number;
}

export const EDITOR_TABS: readonly EditorTab[] = [
  { key: 'basics', label: 'Basics', segment: 'basics', available: true, issue: 33 },
  { key: 'rewards', label: 'Rewards', segment: 'rewards', available: false, issue: 34 },
  { key: 'story', label: 'Story', segment: 'story', available: true, issue: 35 },
  { key: 'prelaunch', label: 'Pre-launch', segment: 'prelaunch', available: true, issue: 39 },
  { key: 'review', label: 'Review', segment: 'review', available: false, issue: 37 },
];

export function editorTabHref(projectId: string, tab: EditorTab): string {
  return `/projects/${encodeURIComponent(projectId)}/edit/${tab.segment}`;
}
