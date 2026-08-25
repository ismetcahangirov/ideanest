import { describe, expect, it } from 'vitest';
import { formatSummary, percentile75, summarise, type Observation } from './summary';

describe('percentile75', () => {
  /*
   * Nearest rank, so the figure reported is one somebody actually experienced.
   * Interpolating would produce a headline number that no session had, and CrUX
   * — the thing this is meant to be comparable with — does not interpolate.
   */
  it.each([
    [[1], 1],
    [[1, 2], 2],
    [[1, 2, 3], 3],
    [[1, 2, 3, 4], 3],
    [[1, 2, 3, 4, 5, 6, 7, 8], 6],
    [[10, 20, 30, 40, 50, 60, 70, 80, 90, 100], 80],
  ])('takes the observation at rank ceil(0.75n) of %s', (values, expected) => {
    expect(percentile75(values)).toBe(expected);
  });

  it('is a value that was measured, never one between two', () => {
    const values = [100, 200, 300, 400];
    expect(values).toContain(percentile75(values));
  });

  it('does not care what order the observations arrived in', () => {
    expect(percentile75([8, 3, 1, 6, 2, 7, 4, 5])).toBe(6);
  });

  it('does not mutate its input', () => {
    const values = [3, 1, 2];
    percentile75(values);
    expect(values).toEqual([3, 1, 2]);
  });

  it('has no answer for no observations', () => {
    expect(percentile75([])).toBeNull();
  });

  /*
   * Worth knowing rather than worth hiding: below four observations the p75 is
   * the maximum. `summarise` prints the count beside every figure so that a p75
   * of three samples can be disbelieved on sight.
   */
  it('degenerates to the maximum for a handful of observations', () => {
    expect(percentile75([5, 900])).toBe(900);
  });
});

describe('summarise', () => {
  const observations: Observation[] = [
    { route: '/[locale]/discover', name: 'LCP', value: 1000 },
    { route: '/[locale]/discover', name: 'LCP', value: 2000 },
    { route: '/[locale]/discover', name: 'LCP', value: 3000 },
    { route: '/[locale]/discover', name: 'LCP', value: 9000 },
    { route: '/[locale]/discover', name: 'CLS', value: 0.02 },
    { route: '/[locale]/projects/[id]/back', name: 'INP', value: 120 },
  ];

  it('groups by route and metric and rates the p75', () => {
    expect(summarise(observations)).toEqual([
      { route: '/[locale]/discover', name: 'CLS', samples: 1, p75: 0.02, rating: 'good' },
      { route: '/[locale]/discover', name: 'LCP', samples: 4, p75: 3000, rating: 'needs-improvement' },
      { route: '/[locale]/projects/[id]/back', name: 'INP', samples: 1, p75: 120, rating: 'good' },
    ]);
  });

  it('sorts, so two runs of the same data print the same table', () => {
    const shuffled = [...observations].reverse();
    expect(summarise(shuffled)).toEqual(summarise(observations));
  });

  it('has nothing to say about nothing', () => {
    expect(summarise([])).toEqual([]);
    expect(formatSummary([])).toContain('No field measurements');
  });
});

describe('formatSummary', () => {
  it('prints the rating words the lab summary prints', () => {
    const table = formatSummary(
      summarise([
        { route: '/[locale]/discover', name: 'LCP', value: 5000 },
        { route: '/[locale]/discover', name: 'CLS', value: 0.4 },
      ]),
    );
    expect(table).toContain('`/[locale]/discover`');
    expect(table).toContain('5000 ms (poor)');
    expect(table).toContain('0.400 (poor)');
  });
});
