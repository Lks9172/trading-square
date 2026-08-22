package io.macrosquare.company.application.port.in;

@FunctionalInterface
public interface RecordCompanyAnalystHistoryUseCase {

    CompanyAnalystHistoryRecordReport recordDailyHistory();
}
