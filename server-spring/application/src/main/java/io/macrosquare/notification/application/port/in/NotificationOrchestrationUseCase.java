package io.macrosquare.notification.application.port.in;

public interface NotificationOrchestrationUseCase {
    boolean dispatchStartup();

    int scanCandidates(String trigger);

    boolean checkMarketChanges(String trigger);

    boolean dispatchWeeklyReport();
}
