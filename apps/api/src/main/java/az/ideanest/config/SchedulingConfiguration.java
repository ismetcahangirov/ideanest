package az.ideanest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler, which is the timer the durable scheduler runs on.
 *
 * <p><strong>The annotation stays, and nothing uses it directly.</strong> §8.4's
 * jobs are registered by {@code JobScheduler} against a {@code TaskScheduler},
 * not by {@code @Scheduled} — {@code JobTriggerTests} refuses a production
 * method that carries it. This is still here because Spring Boot only
 * auto-configures the {@code taskScheduler} bean when scheduling is enabled: the
 * condition is the presence of {@code internalScheduledAnnotationProcessor},
 * which is exactly what {@code @EnableScheduling} registers. Removing it as
 * dead configuration would leave the platform with no timer at all, and the
 * symptom would be sixteen jobs that never run.
 *
 * <p>What #134 changed is not the timer. Every replica still keeps its own, and
 * that is deliberate — a scheduler on one elected replica is a scheduler with a
 * single point of failure and an election to get wrong. What changed is that a
 * trigger now claims a lease before it does anything, so the number of replicas
 * stopped deciding how many times the work happens. See
 * {@code az.ideanest.shared.jobs}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
