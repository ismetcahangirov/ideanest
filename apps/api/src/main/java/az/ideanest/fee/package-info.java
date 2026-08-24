/**
 * What the platform and the processor charge — §9 and §4.11's AD-11, issue #311.
 *
 * <p><strong>Its own module because two others need it and neither should own it.</strong>
 * The payout module subtracts fees from a creator's collections; the console edits them.
 * Putting the schedule in {@code payout} would mean the fee screen reached into the payout
 * module to change a rate, and putting it in {@code payment} would mean the payout run
 * asked the charge log what the platform charges — a question that table has no business
 * answering.
 *
 * <p>Callers name {@code fee.application.FeeSchedules} and get a {@code FeeBreakdown}.
 * Nothing outside this module names {@code FeeSchedule} or reads {@code fee_schedules}.
 */
package az.ideanest.fee;
