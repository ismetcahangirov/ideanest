package az.ideanest.shared.observability;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * Installs §18.1's correlation identifiers.
 *
 * <p>The two filters are registered explicitly rather than picked up as beans,
 * because their positions relative to the security chain are the point and a
 * default order would put them both in the same place.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    /**
     * First, ahead of everything including authentication.
     *
     * <p>§18.1 requires authentication failures and rate-limit rejections to be
     * logged, and neither of those requests ever reaches a handler. A filter
     * ordered after the security chain would leave exactly the lines that matter
     * most without an identifier.
     *
     * <p>Async and error dispatches included: both run on a thread whose MDC is
     * not the request thread's.
     */
    @Bean
    public FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> registration = new FilterRegistrationBean<>(new CorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    /**
     * Last, after the security chain has decided who the caller is.
     *
     * <p>The account cannot be known any earlier, and this filter's whole job is
     * to name it.
     */
    @Bean
    public FilterRegistrationBean<AccountCorrelationFilter> accountCorrelationFilter() {
        FilterRegistrationBean<AccountCorrelationFilter> registration =
                new FilterRegistrationBean<>(new AccountCorrelationFilter());
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    /**
     * Applied by Spring Boot to the auto-configured application task executor, so
     * that {@code @Async} work carries the correlation of the request that started
     * it.
     */
    @Bean
    public TaskDecorator correlationTaskDecorator() {
        return new CorrelationTaskDecorator();
    }

    /**
     * The same decorator on the scheduler, which Boot does not wire from a bean.
     *
     * <p>§8.4's jobs are the lines least likely to be looked at and most likely to
     * matter when they are. Without this a sweep's lines can only be gathered by
     * timestamp.
     */
    @Bean
    public ThreadPoolTaskSchedulerCustomizer correlatedScheduledTasks(TaskDecorator decorator) {
        return scheduler -> scheduler.setTaskDecorator(decorator);
    }
}
