package az.ideanest.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Rules about migrations, enforced rather than described.
 *
 * <p>A migration is the one artefact in the service that cannot be rolled back
 * by redeploying the previous build. By the time a bad one is noticed it has
 * usually run in every environment, so the checks that matter have to happen
 * before it is merged. These are those checks.
 *
 * <p>Deliberately a plain unit test: it reads files and needs no container.
 */
class MigrationConventionTests {

    /**
     * {@code V<version>__<snake_case_description>.sql}. Flyway itself accepts a
     * great deal more; a single shape means the ordering of a directory listing
     * is the ordering of application, and nobody has to wonder whether
     * {@code V2_1} sorts before or after {@code V2.1}.
     */
    private static final Pattern FILENAME = Pattern.compile("^V(\\d+)__[a-z0-9]+(?:_[a-z0-9]+)*\\.sql$");

    /**
     * Statements that destroy data or break a running deployment if the old code
     * is still serving traffic. Permitted, but only when the migration says out
     * loud that it is the contract half of an expand-then-contract change.
     */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?m)^\\s*(DROP\\s+(TABLE|COLUMN|TYPE|SCHEMA)|TRUNCATE|ALTER\\s+TABLE\\s+\\S+\\s+DROP)\\b",
            Pattern.CASE_INSENSITIVE);

    private static List<Path> migrations() {
        Path directory;
        try {
            directory = new ClassPathResource("db/migration").getFile().toPath();
        } catch (IOException e) {
            throw new UncheckedIOException("db/migration is not on the classpath", e);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + directory, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Test
    @DisplayName("there is at least one migration to check")
    void migrationsExist() {
        // Otherwise every rule below passes vacuously and this file becomes a
        // decoration.
        assertThat(migrations()).isNotEmpty();
    }

    @Test
    @DisplayName("every migration is named V<version>__<snake_case>.sql")
    void namesFollowOneShape() {
        for (Path migration : migrations()) {
            String name = migration.getFileName().toString();
            assertThat(FILENAME.matcher(name).matches())
                    .withFailMessage(
                            "Migration '%s' does not match V<version>__<snake_case_description>.sql", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no two migrations claim the same version")
    void versionsAreUnique() {
        List<String> versions = migrations().stream()
                .map(path -> path.getFileName().toString())
                .map(FILENAME::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .toList();

        // Flyway rejects a duplicate at start-up. Finding it here means finding
        // it in the pull request that introduced it rather than in the deploy
        // that happened to merge two branches.
        assertThat(versions).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every migration documents how to reverse it")
    void everyMigrationCarriesItsReverse() {
        for (Path migration : migrations()) {
            String body = read(migration);
            // Flyway's community edition has no `undo`. The reverse therefore
            // has to be written down and reviewed at the same time as the
            // forward change — writing it during an incident, against a schema
            // nobody can see, is how a bad hour becomes a bad week.
            assertThat(body)
                    .withFailMessage(
                            "Migration '%s' has no '-- Reverse:' block. Every migration states how to undo it,"
                                    + " even if the answer is a comment saying the change cannot be undone and why.",
                            migration.getFileName())
                    .contains("-- Reverse:");
        }
    }

    @Test
    @DisplayName("a destructive statement is marked as the contract half of a change")
    void destructiveChangesAreDeclared() {
        for (Path migration : migrations()) {
            String body = read(migration);
            String withoutComments = body.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);

            if (DESTRUCTIVE.matcher(withoutComments).find()) {
                // Under a rolling deployment both versions of the code run at
                // once. Dropping a column the previous version still selects
                // breaks live requests, so the drop is a separate release from
                // the change that stopped using it -- expand, then contract.
                assertThat(body.toUpperCase(Locale.ROOT))
                        .withFailMessage(
                                "Migration '%s' drops or truncates something without a '-- Contract:' block."
                                        + " Expand and contract are separate releases; say which half this is"
                                        + " and which release stopped using what it removes.",
                                migration.getFileName())
                        .contains("-- CONTRACT:");
            }
        }
    }
}
