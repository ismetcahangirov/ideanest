package az.ideanest.shared;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One field of a partial update: absent, or present with a value that may be null.
 *
 * <p><strong>Why this exists.</strong> A {@code PATCH} body has JSON Merge-Patch
 * semantics (RFC 7396): a field that is not mentioned is left alone, and a field
 * explicitly set to {@code null} is cleared. Bound to an ordinary record, both
 * arrive as {@code null} and the two cannot be told apart — so an autosave that
 * sends nothing but the title would clear the summary, the goal, and the story.
 * That is not a hypothetical: the campaign editor autosaves one field at a time.
 *
 * <p>{@link java.util.Optional} does not solve it either. Jackson's JDK 8 module
 * maps both a missing property and an explicit null to {@code Optional.empty()},
 * which collapses exactly the distinction that matters.
 *
 * <p>It lives in {@code shared} rather than in an {@code api} package because a
 * partial update is a value the application layer reasons about — a service
 * method's parameter is "the new title, or nothing" — and only incidentally a
 * thing that arrives over HTTP. {@link Money} carries its own serialisation the
 * same way and for the same reason.
 *
 * <p>The two Jackson hooks that make it work are on {@link PatchedDeserializer}:
 * {@code getAbsentValue} for a property that was not in the document, and
 * {@code getNullValue} for one that was there and was null. Records built from
 * this type should also normalise a {@code null} component to {@link #absent()}
 * in their canonical constructor — belt and braces, so that a field is never
 * accidentally read as "clear this" because a Jackson version stopped consulting
 * the absent hook.
 *
 * <p><strong>It serialises as well as deserialises</strong>, which it did not have to
 * until #56: §10.3's idempotency fingerprints the parsed request by serialising it,
 * so a partial edit that carried a {@code Patched} had to be written down without
 * losing the value. Nothing the platform <em>responds</em> with contains one. See
 * {@link PatchedSerializer}.
 *
 * @param <T> the field's type
 */
@JsonDeserialize(using = PatchedDeserializer.class)
@JsonSerialize(using = PatchedSerializer.class)
public final class Patched<T> {

    private static final Patched<?> ABSENT = new Patched<>(false, null);

    private final boolean present;
    private final T value;

    private Patched(boolean present, T value) {
        this.present = present;
        this.value = value;
    }

    /** The client did not mention this field. Leave whatever is stored alone. */
    @SuppressWarnings("unchecked")
    public static <T> Patched<T> absent() {
        return (Patched<T>) ABSENT;
    }

    /** The client sent this field. A null value means "clear it". */
    public static <T> Patched<T> of(T value) {
        return new Patched<>(true, value);
    }

    /** Turns a possibly-null component into {@link #absent()}. See the class comment. */
    public static <T> Patched<T> orAbsent(Patched<T> patched) {
        return patched == null ? absent() : patched;
    }

    public boolean isPresent() {
        return present;
    }

    /** The value the client sent, which is null when the field is being cleared. */
    public T value() {
        return value;
    }

    /**
     * Applies the change, and does nothing at all when the field was absent.
     *
     * <p>The whole point of the type in one method: a caller that writes
     * {@code patch.title().ifPresent(project::setTitle)} cannot accidentally
     * write null over a title nobody mentioned.
     */
    public void ifPresent(Consumer<T> action) {
        if (present) {
            action.accept(value);
        }
    }

    /**
     * The same field, with its value converted.
     *
     * <p>Used where the shape a client sends is not the shape the domain holds —
     * a cover image as three JSON fields becoming one value object, a story
     * document becoming the text stored in a {@code jsonb} column. Absence
     * survives the conversion, and so does an explicit null: mapping must never
     * turn "clear this" into "leave it alone", because the caller would then see
     * a save that reported success and changed nothing.
     */
    public <R> Patched<R> map(Function<T, R> mapper) {
        if (!present) {
            return absent();
        }
        return of(value == null ? null : mapper.apply(value));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Patched<?> patched
                && present == patched.present
                && Objects.equals(value, patched.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(present, value);
    }

    @Override
    public String toString() {
        return present ? "Patched[" + value + "]" : "Patched[absent]";
    }
}
