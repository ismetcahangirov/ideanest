package az.ideanest.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds §18.1's {@code userId} once the request has been authenticated.
 *
 * <p>Separate from {@link CorrelationFilter}, and ordered after the security
 * chain rather than before it, because the two need opposite positions. The
 * correlation identifier has to be set before authentication runs, so that a
 * rejected request is still traceable; the account is not known until
 * afterwards.
 *
 * <p>The account identifier is the subject of a token this service signed — a
 * UUID, not a name or an address. §17.4 asks for personal data to be kept out of
 * logs, and a pseudonymous identifier is what lets a log be joined to a person's
 * rows without naming the person in the log itself. It is validated on the way in
 * regardless: a claim is still an input.
 */
public class AccountCorrelationFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String account = authenticatedAccount();
        if (account != null) {
            MDC.put(Correlation.USER_ID, account);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (account != null) {
                MDC.remove(Correlation.USER_ID);
            }
        }
    }

    private static String authenticatedAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return Correlation.acceptableIdentifier(authentication.getName());
    }
}
