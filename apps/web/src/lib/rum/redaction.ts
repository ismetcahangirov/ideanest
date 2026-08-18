/**
 * The second lock. `az.ideanest.shared.observability.Redaction`, by shape, on a
 * payload that already cannot carry any of these.
 *
 * <h2>Why this exists when the schema already forbids everything</h2>
 *
 * `payload.ts` validates every string against a closed vocabulary or a strict
 * pattern: a route is one of eleven, a connection is one of five, a session
 * identifier is a v4 UUID. There is no field an email address can be written
 * into, and that is the real guarantee.
 *
 * This runs anyway, over every string of a payload that has already passed, for
 * the reason the service gives for redacting in the encoder rather than at the
 * call site: **a rule that only holds while every future author remembers it is
 * a rule that holds until the first afternoon somebody adds a field.** The day a
 * `referrer` or a `searchTerm` looks harmless enough to add, this is the check
 * that refuses the payload and the test that says why. The cost is a regular
 * expression pass over about two hundred bytes, on a request that is already
 * doing JSON parsing.
 *
 * The rules are the *shape* half of §17.4's list — email, phone, JWT, bearer
 * credential, `otpauth://`, IBAN, primary account number. The field-name half is
 * not reproduced: it would be a list of names this payload has none of, and the
 * schema refuses unknown names outright, which is stricter.
 *
 * **Over-refusing is the safe direction, and here it is nearly free.** A refused
 * beacon is one lost sample out of a percentile computed from thousands. A
 * beacon carrying a backer's email address into a log is a breach.
 *
 * <h2>The one exemption, and why it is not a hole</h2>
 *
 * `payload.ts` runs these rules over every field **except** the four correlation
 * identifiers, which it has already proved to be machine-minted hex or a v4
 * UUID. The reason is arithmetic. A span identifier is sixteen random hex
 * characters, and about one in eighteen hundred of them comes out as sixteen
 * digits; a small fraction of those begin with an issuer prefix and satisfy
 * Luhn. Left in, the card rule would silently reject roughly one beacon in a
 * hundred thousand for looking like a Visa, with no cause anybody could ever
 * find. Taken out, the rules lose nothing: a string that has been proved to
 * match `^[0-9a-f]{16}$` cannot contain an email address, a phone number, a JWT,
 * a space, or a query string, which is every rule below.
 */

/**
 * Anything with a space, a `?`, a `#` or a `%` in it.
 *
 * None of the payload's fields contains any of the four. A `?` or a `#` is a URL
 * that got through as a route; a `%` is the same URL percent-encoded to get past
 * the first two; whitespace is free text of any kind. Refusing them costs
 * nothing and catches the whole family before the specific rules below have to.
 */
const NOT_IN_ANY_FIELD = /[\s?#%]/;

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/;

/** A JWT anywhere, named or not: every JWT header is JSON, so every JWT starts `eyJ`. */
const JWT = /\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*/;

const BEARER = /(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}/i;

const OTPAUTH = /otpauth:\/\//i;

const IBAN = /\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\b/;

/**
 * International, and requiring the literal `+` — without it the rule also
 * matches an ISO timestamp, which is on every line the service logs.
 */
const INTERNATIONAL_PHONE = /(?<![\w+])\+\d[\d\s().-]{7,17}\d(?!\w)/;

/** Local, in the shape Azerbaijani mobile numbers are written in. */
const LOCAL_PHONE = /(?<![\w+])0\d{2}[\s.-]?\d{3}[\s.-]?\d{2}[\s.-]?\d{2}(?!\w)/;

/** Twelve to nineteen digits, single spaces or hyphens allowed. A candidate only. */
const CARD_CANDIDATE = /(?<![0-9])[0-9](?:[ -]?[0-9]){11,18}(?![0-9])/g;

const SHAPES: readonly RegExp[] = [
  OTPAUTH,
  BEARER,
  JWT,
  EMAIL,
  IBAN,
  INTERNATIONAL_PHONE,
  LOCAL_PHONE,
];

/**
 * Whether a string is one this application refuses to write to a log.
 *
 * Deliberately a predicate and not a masker. The service masks because it is
 * salvaging a line that has already been written by code it does not control;
 * here the whole payload is refused, because a beacon this file objects to is a
 * bug in the reporter rather than a message somebody needed.
 */
export function looksIdentifying(text: string): boolean {
  if (NOT_IN_ANY_FIELD.test(text)) return true;
  if (SHAPES.some((shape) => shape.test(text))) return true;
  return containsCardNumber(text);
}

/**
 * A card number, on the two tests a card number always passes: an issuer prefix
 * at a length that issuer uses, and Luhn.
 *
 * Both, rather than "twelve or more digits". A trace identifier is thirty-two
 * hex characters and an epoch timestamp is thirteen digits; refusing either
 * would refuse every beacon. Every real primary account number satisfies both
 * tests, so nothing is lost by asking. Card data never reaches this platform
 * (§17.2, SAQ A), which is a reason to prove it rather than to assume it.
 */
function containsCardNumber(text: string): boolean {
  // `CARD_CANDIDATE` is global, so `lastIndex` survives a call. Reset, or the
  // second string tested in a payload starts scanning from wherever the first
  // one stopped.
  CARD_CANDIDATE.lastIndex = 0;
  for (const match of text.matchAll(CARD_CANDIDATE)) {
    const digits = match[0].replace(/[ -]/g, '');
    if (hasIssuerPrefix(digits) && passesLuhn(digits)) return true;
  }
  return false;
}

function hasIssuerPrefix(digits: string): boolean {
  const length = digits.length;
  const two = Number(digits.slice(0, 2));
  const four = Number(digits.slice(0, 4));
  switch (digits[0]) {
    // Visa.
    case '4':
      return length === 13 || length === 16 || length === 19;
    // Mastercard, including the 2221-2720 range.
    case '5':
      return length === 16 && two >= 51 && two <= 55;
    case '2':
      return length === 16 && four >= 2221 && four <= 2720;
    // American Express, and JCB.
    case '3':
      return (length === 15 && (two === 34 || two === 37)) || (length === 16 && two === 35);
    // Discover, and UnionPay — the network AZ-issued cards most often co-brand with.
    case '6':
      return (
        (length === 16 && (digits.startsWith('6011') || two === 65)) ||
        (two === 62 && length >= 16 && length <= 19)
      );
    default:
      return false;
  }
}

function passesLuhn(digits: string): boolean {
  let sum = 0;
  let doubling = false;
  for (let index = digits.length - 1; index >= 0; index -= 1) {
    let digit = digits.charCodeAt(index) - 48;
    if (doubling) {
      digit *= 2;
      if (digit > 9) digit -= 9;
    }
    sum += digit;
    doubling = !doubling;
  }
  return sum % 10 === 0;
}

/** Every string reachable from a value, however deeply nested. */
export function stringsIn(value: unknown): string[] {
  if (typeof value === 'string') return [value];
  if (Array.isArray(value)) return value.flatMap(stringsIn);
  if (typeof value === 'object' && value !== null) {
    return Object.entries(value).flatMap(([key, nested]) => [key, ...stringsIn(nested)]);
  }
  return [];
}

/** Whether any string anywhere in a value is one this application refuses to log. */
export function carriesIdentifyingData(value: unknown): boolean {
  return stringsIn(value).some(looksIdentifying);
}
