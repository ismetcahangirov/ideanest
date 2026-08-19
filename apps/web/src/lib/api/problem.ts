/**
 * §10.4's error shape — now defined in `@ideanest/api-client` and re-exported here.
 *
 * IT MOVED, AND NOTHING ABOUT IT CHANGED. #136 published the API contract and generated a
 * typed client from it, and CLAUDE.md §3 is explicit that a type shared between packages
 * lives in a shared package rather than being duplicated. This file was that type's only
 * home while the web application was the only client; it is not any more, and a second
 * hand-maintained copy in the mobile application would be two clients branching on two
 * spellings of the same `code`.
 *
 * The module stays because roughly thirty files import `ApiError` from it, and rewriting
 * those imports would be a large diff whose only effect is to make a later reader wonder
 * what changed about the error handling. Nothing did.
 *
 * `problemOf` keeps its name here for the same reason; it is `problemFrom` upstream, which
 * reads better beside `errorFrom` and is what a new caller should use.
 */
export { ApiError, errorFrom, problemFrom as problemOf } from '@ideanest/api-client';
export type { Problem } from '@ideanest/api-client';
