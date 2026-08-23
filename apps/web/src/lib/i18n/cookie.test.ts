import { describe, expect, it } from 'vitest';
import { LOCALE_COOKIE, LOCALE_COOKIE_MAX_AGE_SECONDS } from './locale';
import { readLocaleCookie, writeLocaleCookie } from './cookie';

describe('readLocaleCookie', () => {
  it('finds the language in a cookie string of its own', () => {
    expect(readLocaleCookie(`${LOCALE_COOKIE}=ru`)).toBe('ru');
  });

  it('finds it among the other cookies a browser sends', () => {
    expect(readLocaleCookie(`theme=dark; ${LOCALE_COOKIE}=tr; other=1`)).toBe('tr');
  });

  it('tolerates the spacing a browser actually uses', () => {
    expect(readLocaleCookie(`a=1;${LOCALE_COOKIE}=az`)).toBe('az');
    expect(readLocaleCookie(`a=1;   ${LOCALE_COOKIE}   =   en   `)).toBe('en');
  });

  /*
   * `null` and not the default: "nobody has ever stated a preference" is a different fact
   * from "somebody chose English", and it is the fact that makes a signed-in account's own
   * stored language authoritative on a device that has never seen this cookie.
   */
  it('answers null when there is no preference, rather than guessing one', () => {
    expect(readLocaleCookie('')).toBeNull();
    expect(readLocaleCookie('theme=dark')).toBeNull();
  });

  /*
   * Cookies are user-editable and outlive deployments. A value that is not one of §21.1's
   * languages — a hand-edited cookie, or a language that was removed — means exactly as
   * much as no cookie at all, and must never be forwarded to a PATCH that would refuse it.
   */
  it('treats an unsupported or hand-edited value as no preference', () => {
    expect(readLocaleCookie(`${LOCALE_COOKIE}=de`)).toBeNull();
    expect(readLocaleCookie(`${LOCALE_COOKIE}=`)).toBeNull();
    expect(readLocaleCookie(`${LOCALE_COOKIE}=../../etc/passwd`)).toBeNull();
  });

  /*
   * The dynamic import in `src/i18n/request.ts` interpolates the resolved language into a
   * module path. Nothing that fails this test may ever reach it.
   */
  it('never returns a value that could escape a message-file path', () => {
    expect(readLocaleCookie(`${LOCALE_COOKIE}=${encodeURIComponent('../secrets')}`)).toBeNull();
    expect(readLocaleCookie(`${LOCALE_COOKIE}=en/../../x`)).toBeNull();
  });

  it('does not match a cookie whose name merely ends with the same letters', () => {
    expect(readLocaleCookie(`not_${LOCALE_COOKIE}=ru`)).toBeNull();
  });
});

describe('writeLocaleCookie', () => {
  it('remembers the language for a year, on every path, and survives a cross-site arrival', () => {
    const written: string[] = [];
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => written.join('; '),
      set: (value: string) => {
        written.push(value);
      },
    });

    writeLocaleCookie('az');

    const [cookie] = written;
    expect(cookie).toContain(`${LOCALE_COOKIE}=az`);
    expect(cookie).toContain(`Max-Age=${LOCALE_COOKIE_MAX_AGE_SECONDS}`);
    expect(cookie).toContain('Path=/');
    /*
     * Lax rather than Strict: Strict withholds the cookie on a cross-site navigation, which
     * is the one that renders the first page somebody following a link ever sees — so a
     * Strict cookie would show them the default in the language they had just rejected.
     */
    expect(cookie).toContain('SameSite=Lax');
  });
});
