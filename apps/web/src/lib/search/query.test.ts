import { describe, expect, it } from 'vitest';
import { SEARCH_PATH, SEARCH_QUERY_PARAM, readSearchQuery, searchHref } from './query';

describe('searchHref', () => {
  it('writes the service’s own parameter name', () => {
    expect(SEARCH_QUERY_PARAM).toBe('q');
    expect(searchHref('ceramics')).toBe('/search?q=ceramics');
  });

  it('encodes what was typed rather than pasting it in', () => {
    expect(searchHref('kitab & çay')).toBe('/search?q=kitab+%26+%C3%A7ay');
  });

  it('is a bare /search for an empty phrase, never /search?q=', () => {
    // The two would be one page at two URLs: a duplicate for a crawler, and a second history
    // entry for a reader who pressed Enter on an empty box.
    expect(searchHref('')).toBe(SEARCH_PATH);
    expect(searchHref('   ')).toBe(SEARCH_PATH);
  });
});

describe('readSearchQuery', () => {
  it('reads and trims the phrase', () => {
    expect(readSearchQuery(new URLSearchParams('q=%20ceramics%20'))).toBe('ceramics');
  });

  it('is the empty string when there is no phrase', () => {
    expect(readSearchQuery(new URLSearchParams())).toBe('');
    expect(readSearchQuery(new URLSearchParams('q='))).toBe('');
  });

  it('takes the first of a repeated parameter rather than joining them', () => {
    // The service binds one value. Joining would search for a phrase nobody typed.
    expect(readSearchQuery(new URLSearchParams('q=a&q=b'))).toBe('a');
  });

  it('never folds — that is the service’s job, and a folded echo is the reader’s own language spelled wrong', () => {
    expect(readSearchQuery(new URLSearchParams('q=se%C3%A7im'))).toBe('seçim');
  });

  it('round-trips whatever was written', () => {
    for (const phrase of ['ceramics', 'kitab & çay', 'Ə', '100% wool']) {
      const url = new URL(searchHref(phrase), 'https://ideanest.az');
      expect(readSearchQuery(url.searchParams)).toBe(phrase);
    }
  });
});
