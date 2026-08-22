package io.macrosquare.company.application.port.in;

import java.util.List;

public interface RefreshCompanyResearchSummariesUseCase {

    RefreshReport refreshAll();

    record RefreshReport(int attempted, int written, List<String> failures) {
        public RefreshReport {
            failures = List.copyOf(java.util.Objects.requireNonNull(failures, "failures"));
            if (attempted < 0 || written < 0 || written > attempted) {
                throw new IllegalArgumentException("company refresh counts are inconsistent");
            }
            if (written + failures.size() != attempted) {
                throw new IllegalArgumentException("every attempted company must be written or failed");
            }
        }

        public boolean successful() {
            return failures.isEmpty() && written == attempted;
        }
    }
}
