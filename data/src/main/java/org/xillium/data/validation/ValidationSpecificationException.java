package org.xillium.data.validation;


/**
 * Data validation exception.
 */
@SuppressWarnings("serial")
public class ValidationSpecificationException extends DataValidationException {
    public ValidationSpecificationException() {
    }

    public ValidationSpecificationException(String message) {
        super(message);
    }

    public ValidationSpecificationException(Throwable cause) {
        super(cause);
    }

    public ValidationSpecificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
