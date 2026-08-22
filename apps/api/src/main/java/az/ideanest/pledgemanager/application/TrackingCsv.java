package az.ideanest.pledgemanager.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tracking file a creator uploads, read into rows — §4.8's PM-20.
 *
 * <h2>Why this reads a file at all</h2>
 *
 * <p>Because the alternative is four thousand requests. A campaign hands its parcels
 * to a carrier and gets back one file; PM-20 is called "tracking number import" for
 * that reason, and an API that took one pledge at a time would be an API every creator
 * writes a script against.
 *
 * <p><strong>The first column is {@code pledge_id}, which is the first column of
 * §4.7's CD-11 export.</strong> That is the whole of the round trip: a creator exports
 * their backers, sends the file to the fulfilment partner, gets it back with two more
 * columns filled in, and uploads it here. A key of "backer email" would have been
 * friendlier to type and would have made the import a personal-data upload that
 * matches on a value backers can change.
 *
 * <h2>What it accepts</h2>
 *
 * <ul>
 *   <li>A header row naming the columns, in any order. {@code pledge_id} must be
 *       there; {@code status}, {@code carrier}, {@code tracking_number} and
 *       {@code tracking_url} are read when present.
 *   <li><strong>Unknown columns, ignored.</strong> The file coming back from a
 *       fulfilment partner has their own reference numbers, weights and dates in it,
 *       and a parser that refused the file over a column it did not need would send
 *       the creator to a spreadsheet to delete columns by hand.
 *   <li>Quoted fields, {@code ""} for a quote inside one, and commas and newlines
 *       inside quotes — which carriers put in address columns this import ignores but
 *       still has to get past.
 *   <li>CRLF or LF, and a byte order mark, because the file has been through Excel.
 *   <li>Blank lines, skipped. Excel writes one at the end of every file.
 * </ul>
 *
 * <p>Deliberately not a CSV library. What is needed is one page of code against one
 * shape of file, and the dependency would be a supply-chain risk taken for a parser
 * whose failures here are reported per row anyway.
 */
final class TrackingCsv {

    /**
     * The character Excel writes at the start of a UTF-8 file.
     *
     * <p>A code point rather than the character itself, for {@code BackerExportService}'s
     * reason: a literal byte order mark in a string literal is invisible in every
     * editor, and the next person to touch the line deletes it without seeing it.
     */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    static final String PLEDGE_ID = "pledge_id";

    static final String STATUS = "status";

    static final String CARRIER = "carrier";

    static final String TRACKING_NUMBER = "tracking_number";

    static final String TRACKING_URL = "tracking_url";

    private TrackingCsv() {
    }

    /**
     * One line of the file, with the columns this import understands pulled out.
     *
     * @param line which line of the document it came from, counting the header as 1.
     *     Reported back on a failure, because "row 412" is what a creator can find in
     *     their spreadsheet and a pledge identifier is not
     * @param pledgeId as typed. Parsed later, so a malformed identifier is one row's
     *     failure rather than the file's
     */
    record Row(int line, String pledgeId, String status, String carrier, String trackingNumber, String trackingUrl) {
    }

    /**
     * Reads the document.
     *
     * @param rowCap how many data rows to read before stopping. The rows beyond it are
     *     not silently dropped: the caller reports the file as truncated, following
     *     §4.7's CD-11, where a short fulfilment list that looks complete is the
     *     failure worth engineering against
     * @return every data row, in file order, at most {@code rowCap} of them
     * @throws FulfilmentImportRejectedException when the document has no header, no
     *     {@code pledge_id} column, or no rows at all — the three faults that are
     *     properties of the file rather than of a row in it
     */
    static Parsed parse(String document, int rowCap) {
        List<List<String>> lines = split(document == null ? "" : document);
        if (lines.isEmpty()) {
            throw new FulfilmentImportRejectedException(
                    "FULFILMENT_IMPORT_EMPTY", "The file is empty. It needs a header row and at least one parcel.");
        }

        Map<String, Integer> columns = headerOf(lines.getFirst());
        if (!columns.containsKey(PLEDGE_ID)) {
            throw new FulfilmentImportRejectedException(
                    "FULFILMENT_IMPORT_NO_PLEDGE_COLUMN",
                    "The file needs a 'pledge_id' column. It is the first column of the backer export.");
        }

        List<Row> rows = new ArrayList<>();
        boolean truncated = false;
        for (int index = 1; index < lines.size(); index++) {
            List<String> cells = lines.get(index);
            if (isBlank(cells)) {
                continue;
            }
            if (rows.size() == rowCap) {
                truncated = true;
                break;
            }
            int line = index + 1;
            rows.add(new Row(
                    line,
                    cell(cells, columns, PLEDGE_ID),
                    cell(cells, columns, STATUS),
                    cell(cells, columns, CARRIER),
                    cell(cells, columns, TRACKING_NUMBER),
                    cell(cells, columns, TRACKING_URL)));
        }

        if (rows.isEmpty()) {
            throw new FulfilmentImportRejectedException(
                    "FULFILMENT_IMPORT_EMPTY", "The file has a header and no parcels under it.");
        }
        return new Parsed(rows, truncated);
    }

    /** What the file turned out to hold. */
    record Parsed(List<Row> rows, boolean truncated) {

        Parsed {
            rows = List.copyOf(rows);
        }
    }

    /**
     * The header, folded and trimmed.
     *
     * <p>Case-insensitive because the file has been through a spreadsheet, and a
     * creator whose column says {@code Pledge_ID} has not made a mistake worth
     * refusing a four-thousand-row file over. A duplicate column keeps the first: the
     * alternative is a refusal over something the creator cannot see.
     */
    private static Map<String, Integer> headerOf(List<String> cells) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < cells.size(); index++) {
            String name = cells.get(index).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, index);
            }
        }
        return columns;
    }

    private static String cell(List<String> cells, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= cells.size()) {
            // A short row is not a malformed one: Excel omits trailing empty cells.
            return null;
        }
        String value = cells.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean isBlank(List<String> cells) {
        return cells.stream().allMatch(cell -> cell.trim().isEmpty());
    }

    /**
     * The whole of the CSV grammar this needs: fields separated by commas, optionally
     * quoted, {@code ""} for a quote inside a quoted field, and a record per line
     * except inside quotes.
     */
    private static List<List<String>> split(String document) {
        List<List<String>> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < document.length(); index++) {
            char character = document.charAt(index);
            if (index == 0 && character == BYTE_ORDER_MARK) {
                continue;
            }
            if (quoted) {
                if (character != '"') {
                    cell.append(character);
                } else if (index + 1 < document.length() && document.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> quoted = true;
                case ',' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> {
                    // Swallowed. The line ends at the \n that follows it, and a file
                    // written on a Mac in 1998 is not a case this has to serve.
                }
                case '\n' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    lines.add(List.copyOf(cells));
                    cells.clear();
                }
                default -> cell.append(character);
            }
        }

        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            lines.add(List.copyOf(cells));
        }
        return lines;
    }
}
