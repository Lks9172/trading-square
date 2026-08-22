package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;
import io.macrosquare.company.application.port.in.CompanySubmissionsParityReport;

import java.time.LocalDate;
import java.util.List;

public record CompanySubmissionsParityResponse(
        String ticker,
        String registryCik,
        String selectedCik,
        List<String> submissionCikCandidates,
        boolean allMatched,
        boolean profileMatched,
        boolean filingsMatched,
        int comparedFilingCount,
        int directAvailableFilingCount,
        int legacyEnrichedFilingCount,
        List<String> differences,
        ComparisonResponse result
) {
    static CompanySubmissionsParityResponse from(CompanySubmissionsParityReport report) {
        return new CompanySubmissionsParityResponse(
                report.ticker(),
                report.registryCik(),
                report.selectedCik(),
                report.submissionCikCandidates(),
                report.allMatched(),
                report.profileMatched(),
                report.filingsMatched(),
                report.comparedFilingCount(),
                report.directAvailableFilingCount(),
                report.legacyEnrichedFilingCount(),
                report.differences(),
                new ComparisonResponse(
                        SnapshotResponse.from(report.legacy()),
                        SnapshotResponse.from(report.spring())
                )
        );
    }

    public record ComparisonResponse(SnapshotResponse legacy, SnapshotResponse spring) {
    }

    public record SnapshotResponse(ProfileResponse profile, List<FilingResponse> filings) {
        static SnapshotResponse from(CompanySubmissionsSnapshot value) {
            return new SnapshotResponse(
                    ProfileResponse.from(value.profile()),
                    value.filings().stream().map(FilingResponse::from).toList()
            );
        }
    }

    public record ProfileResponse(String ticker, String cik, String name, String exchange, String sic) {
        static ProfileResponse from(CompanySubmissionsSnapshot.Profile value) {
            return new ProfileResponse(
                    value.ticker(), value.cik(), value.name(), value.exchange(), value.sic()
            );
        }
    }

    public record FilingResponse(
            String accessionNumber,
            LocalDate filingDate,
            String form,
            String primaryDocument,
            String primaryDocDescription,
            boolean isEarningsRelated,
            String filingUrl
    ) {
        static FilingResponse from(CompanySubmissionsSnapshot.Filing value) {
            return new FilingResponse(
                    value.accessionNumber(),
                    value.filingDate(),
                    value.form(),
                    value.primaryDocument(),
                    value.primaryDocumentDescription(),
                    value.earningsRelated(),
                    value.filingUrl()
            );
        }
    }
}
