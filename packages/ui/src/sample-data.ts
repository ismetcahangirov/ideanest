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
