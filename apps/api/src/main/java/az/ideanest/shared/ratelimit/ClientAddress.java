package az.ideanest.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What an anonymous caller is counted as.
 *
 * <p>One copy of a decision that four endpoints had made separately. It is the kind
 * of three-line helper that is cheaper to repeat than to share right up until the
 * day the reasoning below changes, at which point the copies that were not updated
 * are the ones that turn the limiter off.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    /**
     * The remote address as the container saw it.
     *
     * <p>Deliberately not {@code X-Forwarded-For}. Trusting that header without a
     * proxy in front means any client can pick its own rate-limit bucket by inventing
     * one, which turns the limiter off. Behind a load balancer the correct fix is
     * {@code server.forward-headers-strategy}, set per environment where the proxy is
     * known to be there (#139).
     */
    public static String of(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
}
