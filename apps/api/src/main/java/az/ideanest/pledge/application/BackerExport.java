package az.ideanest.pledge.application;

/**
 * §4.7's CD-11 and §4.8's PM-17: the backer report as a file.
 *
 * @param filename what the browser should save it as, including the campaign identifier
 *     and the day it was taken. A creator who exports the same campaign twice in a week
 *     has two files in one folder, and "backers.csv (2)" tells them nothing about which is
 *     which
 * @param csv the whole document, materialised. Bounded by
 *     {@link az.ideanest.pledge.PledgeProperties.Report#exportRowCap()}, which is what
 *     makes materialising it safe
 * @param rows how many backers it describes, not counting the header
 * @param truncated whether the cap was reached and the file is therefore short.
 *     <strong>Reported rather than silent</strong>: a fulfilment list missing its tail
 *     looks exactly like a complete one, and the person who finds out is a backer who
 *     never received their reward
 */
public record BackerExport(String filename, String csv, int rows, boolean truncated) {
}
