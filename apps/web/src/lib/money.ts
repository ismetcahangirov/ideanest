/**
 * Money on the client, which now lives in `@ideanest/money`.
 *
 * <h2>Why the file stayed</h2>
 *
 * The implementation moved when `apps/mobile` arrived (#111's sibling work) and
 * needed to format the same amounts. CLAUDE.md §3 is explicit that types shared
 * between packages live in a shared package and are never duplicated, and money
 * is the last thing on this platform that should have two implementations —
 * `0.1 + 0.2 !== 0.3` is somebody's pledge, and two copies of the rule are two
 * places for one of them to drift.
 *
 * What stayed is this re-export, so that the fifty-odd modules already importing
 * `../../lib/money` did not all have to change in the same commit as the move.
 * A rename touching fifty files hides the one line that matters in a diff nobody
 * finishes reading. New code may import either; `@ideanest/money` is the one to
 * reach for.
 */
export * from '@ideanest/money';
