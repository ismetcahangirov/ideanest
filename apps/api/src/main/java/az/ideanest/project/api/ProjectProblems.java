package az.ideanest.project.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Problem details this module raises from more than one advice.
 *
 * <p>{@link CapabilityNotGrantedException} can come out of any endpoint in the
 * module — a collaborator autosaving a field they may not write, and a manager
 * granting a capability they do not hold, are the same refusal — and the module has
 * two advices, each scoped to its own controllers so that neither catches something
 * broad on the other's behalf. Writing the response twice would be two bodies for
 * one failure, and the second one to be edited would be the one nobody noticed.
 */
final class ProjectProblems {

    private ProjectProblems() {
    }

    /**
     * 403 for a caller who works on the campaign and may not do this.
     *
     * <p>Not a 404: they were invited and can already read the campaign, so the
     * confidentiality that {@code ProjectNotFoundException} protects has nothing left
     * to protect here. The body names what would have authorised the request and what
     * the caller holds, so the editor can disable the control rather than let somebody
     * press it and be refused.
     */
    static ProblemDetail capabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted on this campaign");
        problem.setDetail(
                exception.isCreatorOnly()
                        // Launching and cancelling. No grant confers either, so
                        // "you need capability X" would send the caller to ask for
                        // something that does not exist.
                        ? "Only the campaign's creator can do that."
                        : "Your collaborator access on this campaign does not include that.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");

        Map<String, Object> meta = new LinkedHashMap<>();
        // Empty when the action belongs to the creator alone, which is what tells a
        // client to stop offering it rather than to ask for a capability.
        meta.put("requiredAnyOf", exception.requiredAnyOf());
        meta.put("held", exception.held());
        problem.setProperty("meta", meta);
        return problem;
    }
}
