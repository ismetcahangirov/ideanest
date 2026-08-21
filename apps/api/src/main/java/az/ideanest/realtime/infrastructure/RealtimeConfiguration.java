package az.ideanest.realtime.infrastructure;

import az.ideanest.realtime.RealtimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Where §12.1's socket is served, and who may open one.
 *
 * <h2>Origins are named rather than opened</h2>
 *
 * <p><strong>A WebSocket handshake is not subject to CORS</strong>, which is the single most
 * important thing to know about this file: a browser will let any page on any origin open a
 * socket to this endpoint unless the server checks {@code Origin} itself. Spring's default is
 * to allow the same origin only, and {@link #registerWebSocketHandlers} names the web
 * application's origins explicitly so the rule survives the API and the site being on different
 * hosts — which they are in every environment.
 *
 * <p>What that check is protecting is small, and saying so is the honest framing: these channels
 * carry a counter and a comment count for a public campaign, so an attacker who bypassed it
 * would learn what the campaign page already tells them. The check is here because an
 * unauthenticated socket with an open origin policy is a thing that gets reused later for a
 * channel that does carry something — {@code user:{id}} is in §12.1 and is not built.
 *
 * <p>The list is {@code ideanest.realtime.allowed-origins}, and that record says why it lives
 * there rather than beside a CORS configuration: there is no CORS configuration, because this is
 * the first endpoint on the service a browser calls directly.
 *
 * <h2>No SockJS fallback</h2>
 *
 * <p>Spring offers one and it is not registered. SockJS exists for browsers without WebSocket
 * support, which means Internet Explorer; §21 names no such browser, and the fallback costs a
 * client library, four extra HTTP endpoints and a session layer with its own timeouts. A reader
 * whose network refuses WebSocket keeps the numbers the server rendered, which is the same
 * outcome this module already accepts for a refused connection.
 *
 * <h2>Switched off entirely by one property</h2>
 *
 * <p>{@code ideanest.realtime.enabled: false} and there is no endpoint at all — not an endpoint
 * that accepts and never speaks. That is what the test profile uses, and it is what an incident
 * would use: this is the one module whose absence changes no stored fact.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(prefix = "ideanest.realtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeConfiguration implements WebSocketConfigurer {

    /** §12.1's endpoint. Versioned like every other path in §10.2. */
    private static final String PATH = "/v1/realtime";

    private final RealtimeWebSocketHandler handler;
    private final RealtimeProperties properties;

    public RealtimeConfiguration(RealtimeWebSocketHandler handler, RealtimeProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(handler, PATH);
        if (!properties.allowedOrigins().isEmpty()) {
            registration.setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
        }
        // With no configured origins the registration keeps Spring's default, which is
        // same-origin only. Deliberately not `*`: an empty configuration should be the safe one,
        // and a deployment that has not named its web application yet has no reader to serve.
    }
}
