package io.macrosquare.disclosure.application.service;

import io.macrosquare.disclosure.application.port.in.QueryDartCompanyUseCase;
import io.macrosquare.disclosure.application.port.out.DartRepository;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;

import java.util.Objects;

public final class QueryDartCompanyService implements QueryDartCompanyUseCase {

    private final DartRepository repository;
    private final boolean collectionEnabled;
    private final boolean credentialConfigured;

    public QueryDartCompanyService(DartRepository repository) {
        this(repository, true, true);
    }

    public QueryDartCompanyService(
            DartRepository repository,
            boolean collectionEnabled,
            boolean credentialConfigured
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.collectionEnabled = collectionEnabled;
        this.credentialConfigured = credentialConfigured;
    }

    @Override
    public DartCompanySnapshot query(String stockCode) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("stockCode must contain six digits");
        }
        var snapshot = repository.loadSnapshot(stockCode, 50, 160);
        if (!collectionEnabled) {
            return availability(snapshot, snapshot.company() == null ? "disabled" : "stale",
                    "OpenDART 수집이 비활성화되어 있습니다. 보존된 값은 참고용이며 신규 판단 입력으로 사용하지 않습니다.");
        }
        if (!credentialConfigured) {
            return availability(snapshot, snapshot.company() == null ? "unavailable" : "stale",
                    "OpenDART API 키가 없어 이 소스는 결측으로 처리되며 점수에 포함되지 않습니다.");
        }
        return snapshot;
    }

    private static DartCompanySnapshot availability(
            DartCompanySnapshot source,
            String status,
            String methodology
    ) {
        return new DartCompanySnapshot(
                status,
                source.asOf(),
                source.company(),
                source.disclosures(),
                source.financials(),
                methodology
        );
    }
}
