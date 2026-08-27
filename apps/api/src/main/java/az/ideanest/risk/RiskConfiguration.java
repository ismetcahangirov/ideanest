package az.ideanest.risk;

import az.ideanest.risk.application.AddressGeography;
import az.ideanest.risk.infrastructure.UnresolvedAddressGeography;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The geolocation source this platform does not have — issue #108.
 *
 * <p>One bean, and it is the same shape {@code ChannelSenderConfiguration} used for a
 * channel with no transport behind it: a class named for what it actually is, registered
 * from a configuration rather than annotated onto itself.
 *
 * <p>{@code @ConditionalOnMissingBean} belongs on a {@code @Bean} method and nowhere else.
 * On a scanned {@code @Component} it is evaluated before the scan has found the bean it is
 * asking about, so it matches or does not depending on ordering — which is a defect that
 * appears as a context that will not start, or worse, as two implementations where one was
 * meant.
 *
 * <p><strong>A real implementation is a {@code @Component} implementing
 * {@link AddressGeography} and nothing else changes.</strong> This bean stands aside,
 * {@code RiskFacts} asks the same question, and {@code RiskScorer} already carries the
 * comparison.
 */
@Configuration(proxyBeanMethods = false)
public class RiskConfiguration {

    @Bean
    @ConditionalOnMissingBean(AddressGeography.class)
    AddressGeography unresolvedAddressGeography() {
        return new UnresolvedAddressGeography();
    }
}
