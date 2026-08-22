package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.AddressDestinationMismatchException;
import az.ideanest.pledgemanager.application.AddressLockedException;
import az.ideanest.pledgemanager.application.AddressNotRequiredException;
import az.ideanest.pledgemanager.application.AddressStorageNotConfiguredException;
import az.ideanest.pledgemanager.application.AddressUnreadableException;
import az.ideanest.pledgemanager.application.PledgeNotBackedException;
import az.ideanest.pledgemanager.domain.AddressInvalidException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The address endpoints' failures, as RFC 9457 problem details.
 *
 * <p><strong>No detail here ever contains an address.</strong> A problem detail is a
 * response body, it is logged by clients, and it is pasted into support tickets. What
 * these say is which field was wrong and what the rule is, never what was typed — the
 * same discipline {@code AddressInvalidException} keeps in its messages, for the reason
 * V36 encrypts the column in the first place.
 */
@RestControllerAdvice(assignableTypes = ShippingAddressController.class)
public class ShippingAddressExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ShippingAddressExceptionHandler.class);

    /** 404 for a pledge that is not this caller's, does not exist, or is not a backing. */
    @ExceptionHandler(PledgeNotBackedException.class)
    public ProblemDetail handlePledgeNotBacked(PledgeNotBackedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/pledge-not-found"));
        problem.setTitle("No such pledge");
        problem.setDetail("That pledge does not exist.");
        problem.setProperty("code", "PLEDGE_NOT_FOUND");
        return problem;
    }

    /**
     * 400 naming the field.
     *
     * <p>An address is eight boxes, and a refusal that does not say which one is a
     * refusal somebody answers by re-reading all of them.
     */
    @ExceptionHandler(AddressInvalidException.class)
    public ProblemDetail handleInvalidAddress(AddressInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/address-invalid"));
        problem.setTitle("Invalid address");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "ADDRESS_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /**
     * 409 once the creator has frozen it — §4.8's PM-08.
     *
     * <p>Not a 403. The backer is permitted to edit their own address; what changed is
     * the state of the thing, and a client that said "you are not allowed" would be
     * telling them something false about themselves.
     */
    @ExceptionHandler(AddressLockedException.class)
    public ProblemDetail handleLocked(AddressLockedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/address-locked"));
        problem.setTitle("Address locked");
        problem.setDetail("This campaign has locked its shipping addresses. Contact the creator to change yours.");
        problem.setProperty("code", "ADDRESS_LOCKED");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lockedAt", exception.lockedAt());
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 422 for an address in a country the pledge was not quoted for.
     *
     * <p>The body is well-formed and the address is a valid address; what is wrong is
     * the relationship between the two, which is what 422 is for. The meta names both
     * countries so a client can offer the one thing that actually helps — editing the
     * pledge, which re-quotes shipping.
     */
    @ExceptionHandler(AddressDestinationMismatchException.class)
    public ProblemDetail handleDestinationMismatch(AddressDestinationMismatchException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/address-destination-mismatch"));
        problem.setTitle("Address is not where this pledge ships");
        problem.setDetail("Shipping on this pledge was quoted to " + exception.quoted()
                + ". Change the pledge's destination to ship somewhere else.");
        problem.setProperty("code", "ADDRESS_DESTINATION_MISMATCH");
        problem.setProperty("meta", Map.of("quoted", exception.quoted(), "given", exception.given()));
        return problem;
    }

    /** 422 for a pledge with nothing to post. */
    @ExceptionHandler(AddressNotRequiredException.class)
    public ProblemDetail handleNotRequired(AddressNotRequiredException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/address-not-required"));
        problem.setTitle("Nothing to post");
        problem.setDetail("This pledge has nothing shipped, so it needs no address.");
        problem.setProperty("code", "ADDRESS_NOT_REQUIRED");
        return problem;
    }

    /**
     * 503 when the deployment has no encryption key.
     *
     * <p>The caller did nothing wrong and a retry in a minute will not help either, but
     * 503 is still the honest status: the platform is temporarily unable to serve this
     * feature and the fix is an operator's. Logged at {@code error} because nothing
     * else will notice — every other endpoint keeps working.
     */
    @ExceptionHandler(AddressStorageNotConfiguredException.class)
    public ProblemDetail handleNotConfigured(AddressStorageNotConfiguredException exception) {
        log.error(
                "An address could not be stored: ideanest.pledge-manager.addresses.primary-key-id is not configured."
                        + " Addresses are refused until it is, because storing one unencrypted would break §17.2"
                        + " silently.");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("https://ideanest.az/problems/address-storage-unavailable"));
        problem.setTitle("Addresses cannot be stored");
        problem.setDetail("This platform is not currently able to store shipping addresses. Please try again later.");
        problem.setProperty("code", "ADDRESS_STORAGE_UNAVAILABLE");
        return problem;
    }

    /**
     * 500 when a stored address cannot be decrypted.
     *
     * <p>Never an empty address. A backer shown a blank form would fill it in, the
     * write would replace a row that was merely unreadable rather than absent, and an
     * incident would become data loss.
     */
    @ExceptionHandler(AddressUnreadableException.class)
    public ProblemDetail handleUnreadable(AddressUnreadableException exception) {
        log.error("A stored shipping address could not be read", exception);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create("https://ideanest.az/problems/address-unreadable"));
        problem.setTitle("Address could not be read");
        problem.setDetail("We could not read this address. Please contact support rather than entering it again.");
        problem.setProperty("code", "ADDRESS_UNREADABLE");
        return problem;
    }

    /** 404 for a campaign that does not exist, and for one this caller has no part in. */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That campaign does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /** 403 for a collaborator whose grant does not include {@code VIEW_FINANCES}. */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have permission to manage this campaign's shipping addresses.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }
}
