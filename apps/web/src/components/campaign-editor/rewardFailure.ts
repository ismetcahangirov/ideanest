import type { SaveFailure } from './useAutosave';

/**
 * A refusal from the reward module, placed on the control it is about.
 *
 * <h3>THE SERVICE REFUSES A FIELD IN TWO DIFFERENT SHAPES</h3>
 *
 * Bean validation on a creation body answers `400` with an `errors` map keyed
 * by field name — `ApiExceptionHandler.handleMethodArgumentNotValid`. Everything
 * `ItemService` and `RewardService` refuse themselves answers `400
 * REWARD_FIELD_INVALID` with the field in `meta.field` and the sentence in
 * `detail`, because a service refusal is about one field and there is no
 * binding result to build a map from.
 *
 * Both are the same fact — "this field, this reason" — so both are read here.
 * A form that handled only the first would put "That is longer than 80
 * characters" in a banner above a drawer with fourteen controls in it, which
 * is the difference between a creator fixing it and a creator writing to
 * support.
 *
 * Anything naming a field this form does not have is dropped rather than
 * rendered beside a control it is not about. The whole sentence is still shown
 * in the failure banner, so nothing is lost by dropping it here.
 *
 * Written once and shared by both editors because they consume the same two
 * shapes from the same module; two copies would diverge the first time a third
 * shape appeared.
 */
export function fieldErrorsFrom<Field extends string>(
  failure: SaveFailure | null,
  isField: (value: string) => value is Field,
): Partial<Record<Field, string>> {
  if (failure === null) return {};

  const mapped: Partial<Record<Field, string>> = {};

  for (const [key, message] of Object.entries(failure.fieldErrors)) {
    // `price.amount` is about the price field. The server keys nested bodies by
    // path, and the control is named by the first segment of it.
    const [field = ''] = key.split('.');
    if (isField(field)) mapped[field] = message;
  }

  if (failure.code === 'REWARD_FIELD_INVALID') {
    const field = failure.meta?.field;
    if (typeof field === 'string' && isField(field)) {
      // The service's own sentence wins over anything mapped above: it knows
      // which of its rules was broken and this function cannot.
      mapped[field] = failure.message;
    }
  }

  return mapped;
}
