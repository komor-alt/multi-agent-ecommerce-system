package com.ecommerce.config;

import com.ecommerce.service.RunDeadlineExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;

/** Return stable error codes rather than database/model exception messages. */
@RestControllerAdvice(assignableTypes = RecommendationController.class)
public class RecommendationExceptionAdvice {
    @ExceptionHandler(com.ecommerce.runtime.persistence.StaleExecutionLeaseException.class)
    ProblemDetail staleExecution() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Recommendation execution was superseded; query the existing run");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Conflicting persisted recommendation state");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid recommendation request");
    }

    @ExceptionHandler(RunDeadlineExceededException.class)
    ProblemDetail deadlineExceeded() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, "Recommendation deadline exceeded");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail failedState(IllegalStateException error) {
        if (error.getMessage() != null && error.getMessage().startsWith("RECOMMENDATION_RUN_ALREADY_EXISTS")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Run ID already exists");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Recommendation service unavailable");
    }
}
