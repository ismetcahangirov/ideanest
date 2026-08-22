package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.SurveyDetail;
import java.util.List;

/**
 * A campaign's surveys, newest first.
 *
 * <p>An object with one field rather than a bare array, as every list response here is:
 * a top-level array cannot gain a field, and the first thing this one will want is the
 * campaign's overall response rate.
 */
public record SurveyListResponse(List<SurveyResponseBody> surveys) {

    public static SurveyListResponse of(List<SurveyDetail> surveys) {
        return new SurveyListResponse(surveys.stream().map(SurveyResponseBody::of).toList());
    }
}
