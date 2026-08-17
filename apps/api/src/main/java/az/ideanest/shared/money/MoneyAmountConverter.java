package az.ideanest.shared.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

/**
 * Holds a money column to {@link Money}'s rules, in both directions.
 *
 * <p><strong>Why this converts an amount and not a {@code Money}.</strong> §7.2 stores
 * an amount as {@code numeric(14,2)} and the currency as a <em>separate</em> column,
 * shared by every amount on the row: a {@code pledges} row has five amounts —
 * {@code base_amount}, {@code addons_amount}, {@code bonus_amount},
 * {@code shipping_amount}, {@code tax_amount} — and one {@code currency}. A
 * {@code Money} therefore does not correspond to one column, and the two mappings
 * that would make it look as though it did are both wrong here:
 *
 * <ul>
 *   <li>An {@code AttributeConverter<Money, BigDecimal>} would have nowhere to read the
 *       currency back from, so it would have to assume one. An assumed currency on a
 *       charge is the failure this whole package exists to prevent.</li>
 *   <li>An {@code @Embeddable} pair would need the row's single currency column mapped
 *       once per amount, and {@code projects.goal_amount} is nullable while
 *       {@code projects.currency} is not — so Hibernate would hand a null amount and a
 *       present currency to a constructor that refuses exactly that combination, and a
 *       draft campaign would become unreadable.</li>
 * </ul>
 *
 * <p>So the entity keeps the two columns and assembles a {@link Money} at its edge
 * with {@link Money#of} or {@link Money#orNull}, and this converter guards the amount
 * half: an amount with a place {@code numeric(14,2)} cannot hold is refused rather
 * than rounded by PostgreSQL on the way in, and an amount read back at whatever scale
 * a generated column, a {@code SUM}, or a {@code COALESCE} produced is normalised, so
 * two reads of one row cannot differ by their scale.
 *
 * <p><strong>Not {@code autoApply}.</strong> Latitude, longitude, and §7.2's ranking
 * weights are {@link BigDecimal} too and are not money; applying this to every
 * {@code BigDecimal} in the service would refuse a coordinate at four decimal places.
 * An entity opts a column in:
 *
 * <pre>{@code
 * @Convert(converter = MoneyAmountConverter.class)
 * @Column(name = "goal_amount")
 * private BigDecimal goalAmount;
 * }</pre>
 */
@Converter
public class MoneyAmountConverter implements AttributeConverter<BigDecimal, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(BigDecimal attribute) {
        // Null is not zero. goal_amount is null while a campaign is a draft, and
        // §5.3's checklist reads that column to decide whether a goal was set.
        return attribute == null ? null : MoneyRounding.exactAtColumnScale(attribute);
    }

    @Override
    public BigDecimal convertToEntityAttribute(BigDecimal dbData) {
        // Anything already in the column fits the column, so this is a
        // normalisation and not a repair -- and it is done with the exact() path
        // rather than round() so that a value which somehow does not fit is a loud
        // failure instead of a silently altered amount.
        return dbData == null ? null : MoneyRounding.exactAtColumnScale(dbData);
    }
}
