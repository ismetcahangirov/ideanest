package az.ideanest.shared;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link EmailAddress} to the {@code citext} column holding it.
 *
 * <p>Applied automatically, which is the point: a query parameter of this type
 * is converted the same way the stored value was, so a lookup cannot compare a
 * raw string against a normalised column.
 */
@Converter(autoApply = true)
public class EmailAddressConverter implements AttributeConverter<EmailAddress, String> {

    @Override
    public String convertToDatabaseColumn(EmailAddress attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public EmailAddress convertToEntityAttribute(String dbData) {
        // Anything already in the column was normalised on the way in, so this
        // is a construction, not a repair. If it throws, the row is malformed
        // and reading it as though it were fine would spread the problem.
        return dbData == null ? null : new EmailAddress(dbData);
    }
}
