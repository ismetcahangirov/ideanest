package az.ideanest.admin.application;

/**
 * More identifiers than one directory lookup will answer about — issue #402.
 *
 * <p>A refusal rather than a truncation, for the reason
 * {@link ConsoleDirectoryService#MAX_IDENTIFIERS} states: a screen answered about fewer
 * rows than it asked about renders the remainder as bare identifiers with no name and no
 * explanation, which is the defect this endpoint exists to remove.
 */
public class TooManyIdentifiersException extends RuntimeException {

    private final int asked;
    private final int limit;

    public TooManyIdentifiersException(int asked, int limit) {
        super("A directory lookup takes at most %d identifiers in total; %d were sent".formatted(limit, asked));
        this.asked = asked;
        this.limit = limit;
    }

    public int asked() {
        return asked;
    }

    public int limit() {
        return limit;
    }
}
