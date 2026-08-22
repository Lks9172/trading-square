package io.macrosquare.company.domain.horizon;

import java.util.Objects;

public record CompanyHorizonView(
        CompanyHorizonSignal shortTerm,
        CompanyHorizonSignal swingTerm,
        CompanyHorizonSignal longTerm
) {
    public CompanyHorizonView {
        Objects.requireNonNull(shortTerm, "shortTerm");
        Objects.requireNonNull(swingTerm, "swingTerm");
        Objects.requireNonNull(longTerm, "longTerm");
    }
}
