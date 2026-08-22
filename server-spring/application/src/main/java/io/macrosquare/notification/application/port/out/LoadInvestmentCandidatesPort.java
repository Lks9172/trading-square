package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.domain.InvestmentCandidate;

import java.util.List;

public interface LoadInvestmentCandidatesPort {
    List<InvestmentCandidate> loadStartupCandidates();

    List<InvestmentCandidate> loadScanUniverse();
}
