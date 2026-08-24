package az.ideanest.payout.api;

import az.ideanest.payment.application.NoPayoutProviderException;
import az.ideanest.payout.application.NothingToPayException;
import az.ideanest.payout.application.PayoutAlreadyInFlightException;
import az.ideanest.payout.application.PayoutNotApprovableException;
import az.ideanest.payout.application.PayoutNotFoundException;
import az.ideanest.payout.application.PayoutNotSendableException;
import az.ideanest.payout.application.UnknownPayoutCampaignException;
import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AD-05's payout refusals — issues #69 and #306.
 *
 * <p>Seven handlers rather than one over a shared supertype, and that is deliberate: each
 * carries a different {@code code} and leads the reader to a different next action. A base
 * class would invite an advice that caught it and flattened all seven into "the payout
 * could not be processed", which is the sentence support tickets are made of.
 */
@RestControllerAdvice(assignableTypes = PayoutController.class)
public class PayoutExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /** 404 for a payout identifier that names nothing. */
    @ExceptionHandler(PayoutNotFoundException.class)
    public ProblemDetail handleNotFound(PayoutNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/payout-not-found"));
        problem.setTitle("No such payout");
        problem.setDetail("No payout with that identifier.");
        problem.setProperty("code", "PAYOUT_NOT_FOUND");
        return problem;
    }

    /** 404 for a campaign that does not exist. */
    @ExceptionHandler(UnknownPayoutCampaignException.class)
    public ProblemDetail handleUnknownCampaign(UnknownPayoutCampaignException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such campaign");
        problem.setDetail("No campaign with that identifier.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 409 when the campaign already has a payout on its way.
     *
     * <p>The existing payout's identifier travels in {@code meta}, because the next thing
     * the reader wants is to open it rather than to try again.
     */
    @ExceptionHandler(PayoutAlreadyInFlightException.class)
    public ProblemDetail handleInFlight(PayoutAlreadyInFlightException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/payout-already-in-flight"));
        problem.setTitle("A payout is already in flight");
        problem.setDetail("This campaign already has a payout waiting. Open that one rather than starting another.");
        problem.setProperty("code", "PAYOUT_ALREADY_IN_FLIGHT");
        problem.setProperty("meta", Map.of("payoutId", exception.existingPayoutId().toString()));
        return problem;
    }

    /** 409 when there is nothing left to pay. */
    @ExceptionHandler(NothingToPayException.class)
    public ProblemDetail handleNothingToPay(NothingToPayException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/nothing-to-pay"));
        problem.setTitle("Nothing to pay out");
        problem.setDetail("After fees and refunds this campaign has nothing left to send.");
        problem.setProperty("code", "NOTHING_TO_PAY");
        return problem;
    }

    /**
     * 409 when the payout is not waiting for a signature.
     *
     * <p>The state travels, because the two causes lead somewhere different: still inside
     * its hold is a payout to come back to, and already sent is one somebody else handled.
     */
    @ExceptionHandler(PayoutNotApprovableException.class)
    public ProblemDetail handleNotApprovable(PayoutNotApprovableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/payout-not-approvable"));
        problem.setTitle("Not waiting for approval");
        problem.setDetail("This payout is " + exception.state() + " and is not waiting for a signature.");
        problem.setProperty("code", "PAYOUT_NOT_APPROVABLE");
        problem.setProperty("meta", Map.of("state", exception.state().name()));
        return problem;
    }

    /**
     * 409 when the payout cannot be sent.
     *
     * <p>Also the answer when the figures moved underneath it — a refund issued since the
     * calculation. The payout has been cancelled by then, so the console's next step is to
     * calculate a fresh one, and the state in {@code meta} says so.
     */
    @ExceptionHandler(PayoutNotSendableException.class)
    public ProblemDetail handleNotSendable(PayoutNotSendableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/payout-not-sendable"));
        problem.setTitle("Cannot be sent");
        problem.setDetail("This payout is " + exception.state()
                + ". If the figures changed since it was calculated, work out a new one.");
        problem.setProperty("code", "PAYOUT_NOT_SENDABLE");
        problem.setProperty("meta", Map.of("state", exception.state().name()));
        return problem;
    }

    /**
     * 503 when the deployment has no provider that can send money.
     *
     * <p>Not 500: it is a deployment fact rather than a defect, and it becomes untrue the
     * moment an adapter is configured.
     */
    @ExceptionHandler(NoPayoutProviderException.class)
    public ProblemDetail handleNoProvider(NoPayoutProviderException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("https://ideanest.az/problems/no-payout-provider"));
        problem.setTitle("No provider configured");
        problem.setDetail("This deployment has no payment provider configured to send a payout.");
        problem.setProperty("code", "NO_PAYOUT_PROVIDER");
        return problem;
    }
}
