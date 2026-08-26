package az.ideanest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The credential a metrics scraper presents — §18, issue #138.
 *
 * <p>Both halves come from the deployment. There is no default password and there deliberately
 * is no fallback: {@link MetricsScrapeSecurity} is conditional on this being set, and
 * `application.yml` exposes the Prometheus endpoint only when it is. A deployment that has not
 * configured a credential gets no metrics endpoint rather than an open one.
 *
 * @param username what the scraper sends. Defaulted, because it is not a secret and asking an
 *     operator to invent one is asking them to make a decision that has no consequences
 * @param password the secret. No default — see above
 */
@ConfigurationProperties(prefix = "ideanest.metrics.scrape")
public record MetricsScrapeProperties(String username, String password) {

    public MetricsScrapeProperties {
        username = username == null || username.isBlank() ? "prometheus" : username;
    }
}
