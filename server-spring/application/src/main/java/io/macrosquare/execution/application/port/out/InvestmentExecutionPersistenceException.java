package io.macrosquare.execution.application.port.out;

public final class InvestmentExecutionPersistenceException extends RuntimeException {
    public InvestmentExecutionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvestmentExecutionPersistenceException(String message) {
        super(message);
    }
}
