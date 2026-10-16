package com.tapin.scheduling;

import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database is the final authority on "no overlapping active sessions" ({@code ex_training_sessions_no_overlap}):
 * a service-level pre-check gives a friendly message, the constraint covers concurrent requests.
 */
final class SessionOverlap {

    static final String CONSTRAINT = "ex_training_sessions_no_overlap";
    static final String MESSAGE = "Termin se preklapa s drugim aktivnim treningom.";

    private SessionOverlap() {
    }

    static ApiException overlap() {
        return new ApiException(ErrorCode.TRAINING_OVERLAP, MESSAGE);
    }

    /** @return {@code 409 TRAINING_OVERLAP} for the overlap constraint; any other violation is rethrown */
    static ApiException translate(DataIntegrityViolationException e) {
        if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(CONSTRAINT)) {
            return overlap();
        }
        throw e;
    }
}
