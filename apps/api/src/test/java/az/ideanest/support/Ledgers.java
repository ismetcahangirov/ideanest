package az.ideanest.support;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the financial rows a suite wrote, by turning V41's append-only triggers off
 * for the length of one statement.
 *
 * <p><strong>This is the one place in the suite that goes round a production
 * guarantee, and it has to exist.</strong> {@code transactions} and
 * {@code ledger_entries} refuse {@code UPDATE}, {@code DELETE} and {@code TRUNCATE}, and
 * both reference {@code projects} and {@code pledges} with {@code ON DELETE NO ACTION}.
 * So a suite that collects anything leaves rows that make every <em>other</em> suite's
 * {@code DELETE FROM projects} fail — which is not a bug in either of them, it is two
 * correct rules meeting.
 *
 * <p>The alternatives were considered and are worse:
 *
 * <ul>
 *   <li><strong>Cascade the foreign keys.</strong> That would mean deleting a campaign
 *       destroys the record that its backers were charged, which is precisely what §22.1
 *       and §19.4 say must not be possible.
 *   <li><strong>Leave the rows.</strong> Every suite that deletes campaigns would have to
 *       know about payments, and the failure would arrive in whichever suite happened to
 *       run next.
 *   <li><strong>Give the tests their own schema.</strong> A second container, a second
 *       Flyway run, and a context that no longer matches the one every other test shares.
 * </ul>
 *
 * <p><strong>Disabling the trigger does not weaken what is asserted.</strong>
 * {@code LedgerSchemaTests} watches both triggers refuse an {@code UPDATE} and a
 * {@code DELETE} with them enabled, which is the property under test; this is teardown,
 * runs after the assertions, and re-enables them before it returns.
 */
public final class Ledgers {

    private Ledgers() {}

    /**
     * Every transaction and ledger entry, gone.
     *
     * <p>Entries first: they reference the transactions. The triggers are re-enabled in a
     * {@code finally}, so a failed delete cannot leave the suite running against tables
     * that would accept an edit — which would make {@code LedgerSchemaTests} pass for the
     * wrong reason.
     */
    public static void clear(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_is_append_only");
        jdbc.execute("ALTER TABLE transactions DISABLE TRIGGER transactions_is_append_only");
        try {
            jdbc.update("DELETE FROM ledger_entries");
            jdbc.update("DELETE FROM transactions");
        } finally {
            jdbc.execute("ALTER TABLE transactions ENABLE TRIGGER transactions_is_append_only");
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_is_append_only");
        }
    }
}
