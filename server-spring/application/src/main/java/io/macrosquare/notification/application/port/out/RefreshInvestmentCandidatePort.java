package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.domain.InvestmentCandidate;

/** Refreshes one persisted notification candidate from Spring-owned live evidence. */
@FunctionalInterface
public interface RefreshInvestmentCandidatePort {

    InvestmentCandidate refresh(InvestmentCandidate candidate);
}
