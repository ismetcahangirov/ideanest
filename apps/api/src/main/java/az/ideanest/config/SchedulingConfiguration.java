package az.ideanest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's in-process scheduler.
 *
 * <p><strong>A placeholder for a real one.</strong> §8.4 lists thirteen
 * scheduled jobs, most of them handling money, and none of them can run on a
 * timer that keeps no record of what it did, retries nothing, and fires once per
 * replica. The durable scheduler is #134.
 *
 * <p>What runs here today is account anonymisation, which is the one job whose
 * properties suit this: idempotent, bounded, and indifferent to running an hour
 * late. When #134 lands, this configuration and the {@code @Scheduled} on
 * {@code AccountAnonymisationJob} both go, and the job is registered with the
 * scheduler instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
