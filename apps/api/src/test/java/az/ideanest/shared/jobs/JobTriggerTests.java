package az.ideanest.shared.jobs;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * There is one way for work to be triggered, checked rather than described.
 *
 * <p>{@code @Scheduled} is not wrong, it is unclaimed: it fires on every replica and
 * nothing anywhere records that it did. #134 replaced it, and the replacement is
 * only worth anything while it is the only door — one method that keeps the
 * annotation is one job that still runs once per replica, and it would be found by
 * whatever it did twice rather than by anybody reading it.
 *
 * <p>Deliberately a plain unit test: it reads class files and needs no container.
 */
class JobTriggerTests {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("az.ideanest");

    @Test
    @DisplayName("there are classes to check")
    void classesWereImported() {
        assertThat(PRODUCTION_CLASSES).isNotEmpty();
    }

    @Test
    @DisplayName("nothing schedules itself in process any more")
    void noMethodCarriesTheScheduledAnnotation() {
        // A job says when it wants to run by implementing ScheduledJob, and
        // JobScheduler registers it against the lease. The annotation would bypass
        // both: no claim, no attempt count, no record that the tick happened.
        noMethods()
                .should()
                .beAnnotatedWith(Scheduled.class)
                .because("§8.4's jobs run on the durable scheduler (#134), which claims a lease before"
                        + " the work; @Scheduled fires on every replica and claims nothing")
                .check(PRODUCTION_CLASSES);
    }
}
