/**
 * Sample data shared across stories.
 *
 * Kept in one place so visual-regression snapshots stay stable and every story
 * exercises the same edge cases: an overfunded campaign, one closing today, and
 * one that has barely started.
 */

export interface SampleProject {
  id: string;
  title: string;
  creator: string;
  category: string;
  city: string;
  percent: number;
  daysLeft: number;
  /** Closing imminently — renders as the lime "active" card. */
  urgent?: boolean;
}

export const SAMPLE_PROJECTS: SampleProject[] = [
  {
    id: '1',
    title: 'Starfall Tabletop Game',
    creator: 'Rowan Hale',
    category: 'Games',
    city: 'Bristol',
    percent: 143,
    daysLeft: 0,
    urgent: true,
  },
  {
    id: '2',
    title: 'Woven Archive: Heritage Rugs',
    creator: 'Amara Osei',
    category: 'Art',
    city: 'Lisbon',
    percent: 87,
    daysLeft: 12,
  },
  {
    id: '3',
    title: 'Pomegranate Portable Battery',
    creator: 'Elias Nordin',
    category: 'Technology',
    city: 'Tallinn',
    percent: 64,
    daysLeft: 21,
  },
  {
    id: '4',
    title: 'The Slow Bakery Cookbook',
    creator: 'Nina Halvorsen',
    category: 'Publishing',
    city: 'Bergen',
    percent: 45,
    daysLeft: 30,
  },
  {
    id: '5',
    title: 'Field Recordings, Vol. 1',
    creator: 'Kamran Aliev',
    category: 'Music',
    city: 'Baku',
    percent: 22,
    daysLeft: 41,
  },
];

export const SAMPLE_CATEGORIES = [
  'All',
  'Technology',
  'Games',
  'Design',
  'Art',
  'Publishing',
  'Music',
  'Film',
  'Food',
  'Fashion',
  'Photography',
  'Theatre',
] as const;

/**
 * A stand-in cover and the low-quality placeholder that goes with it.
 *
 * Inline rather than a file on disk, for the reason `visual-regression.test.tsx`
 * gives about determinism: a story that fetches anything renders differently
 * depending on whether the fetch finished, and Storybook's own static server is
 * not running under Vitest. Both are `data:` URIs, so the stories paint the same
 * bytes in the browser and in the snapshot.
 *
 * The placeholder is 16 pixels wide, which is the width `apps/web/src/lib/images/lqip.ts`
 * samples uploads at.
 */
export const SAMPLE_COVER =
  'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI2NDAiIGhlaWdodD0iMzYwIiB2aWV3Qm94PSIwIDAgNjQwIDM2MCI+PGRlZnM+PGxpbmVhckdyYWRpZW50IGlkPSJnIiB4MT0iMCIgeTE9IjAiIHgyPSIxIiB5Mj0iMSI+PHN0b3Agb2Zmc2V0PSIwIiBzdG9wLWNvbG9yPSIjMUYxRjFGIi8+PHN0b3Agb2Zmc2V0PSIxIiBzdG9wLWNvbG9yPSIjOTRCQzE1Ii8+PC9saW5lYXJHcmFkaWVudD48L2RlZnM+PHJlY3Qgd2lkdGg9IjY0MCIgaGVpZ2h0PSIzNjAiIGZpbGw9InVybCgjZykiLz48L3N2Zz4=';

export const SAMPLE_COVER_PLACEHOLDER =
  'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNiIgaGVpZ2h0PSI5IiB2aWV3Qm94PSIwIDAgMTYgOSI+PGRlZnM+PGxpbmVhckdyYWRpZW50IGlkPSJnIiB4MT0iMCIgeTE9IjAiIHgyPSIxIiB5Mj0iMSI+PHN0b3Agb2Zmc2V0PSIwIiBzdG9wLWNvbG9yPSIjMkEyQTJBIi8+PHN0b3Agb2Zmc2V0PSIxIiBzdG9wLWNvbG9yPSIjOTRCQzE1Ii8+PC9saW5lYXJHcmFkaWVudD48L2RlZnM+PHJlY3Qgd2lkdGg9IjE2IiBoZWlnaHQ9IjkiIGZpbGw9InVybCgjZykiLz48L3N2Zz4=';
