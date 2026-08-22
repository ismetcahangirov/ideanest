package az.ideanest.payment;

import az.ideanest.payment.application.StoredCards;
import az.ideanest.payment.infrastructure.UnavailableStoredCards;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one bean in this module that has to be conditional.
 *
 * <p>Everything else the payment module contributes is a plain {@code @Component}.
 * {@link StoredCards} is not, because it is a port whose real implementation does not
 * exist yet: #55 will read {@code payment_methods}, and until then
 * {@link UnavailableStoredCards} answers "there is no card on file" — which is true
 * rather than stubbed. {@link ConditionalOnMissingBean} is what lets #55 replace it by
 * simply existing, and what lets a test supply its own without a clash.
 *
 * <p><strong>There is deliberately no conditional default for {@code PaymentProvider}.</strong>
 * The same shape would look reasonable and would be the stub §9.2 refuses: a fallback
 * adapter is an adapter, and one that answered anything at all would make the
 * collection path look finished. The absence of a provider is handled by
 * {@code PaymentProviders} finding none and {@code CollectionRun} refusing to run.
 */
@Configuration
public class PaymentConfiguration {

    @Bean
    @ConditionalOnMissingBean(StoredCards.class)
    StoredCards unavailableStoredCards() {
        return new UnavailableStoredCards();
    }
}
