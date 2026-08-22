package io.macrosquare.disclosure.domain.model;

import java.time.LocalDate;

public record DartDisclosure(
        String receiptNumber,
        String corpCode,
        String corpName,
        String reportName,
        String filerName,
        LocalDate receivedOn,
        String remark,
        DartEventType eventType,
        String sourceUrl
) {
    public DartDisclosure {
        if (receiptNumber == null || !receiptNumber.matches("\\d{14}")) {
            throw new IllegalArgumentException("receiptNumber is invalid");
        }
        if (corpCode == null || !corpCode.matches("\\d{8}")) throw new IllegalArgumentException("corpCode is invalid");
        if (corpName == null || corpName.isBlank() || reportName == null || reportName.isBlank()) {
            throw new IllegalArgumentException("DART disclosure identity is incomplete");
        }
        filerName = filerName == null ? "" : filerName.trim();
        remark = remark == null ? "" : remark.trim();
        if (receivedOn == null || eventType == null) throw new IllegalArgumentException("DART disclosure metadata is incomplete");
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("sourceUrl is required");
    }
}
