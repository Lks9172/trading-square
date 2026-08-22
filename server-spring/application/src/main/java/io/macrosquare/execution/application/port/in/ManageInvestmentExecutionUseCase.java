package io.macrosquare.execution.application.port.in;

import io.macrosquare.execution.application.model.InvestmentPlanPatch;
import io.macrosquare.execution.application.model.TradeLogCommand;
import io.macrosquare.execution.domain.model.AssetTrancheSummary;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TrancheEntry;

import java.util.List;

public interface ManageInvestmentExecutionUseCase {

    InvestmentPlan investmentPlan();

    InvestmentPlan updateInvestmentPlan(InvestmentPlanPatch patch);

    TrancheWriteResult recordTranche(String asset, int stage, Double priceAtEntry);

    TrancheBook trancheBook();

    int clearTranches(String asset);

    List<TradeLogEntry> recentTradeLog(int limit);

    TradeLogWriteResult appendTradeLog(TradeLogCommand command);

    record TrancheWriteResult(TrancheEntry entry, int total) {
    }

    record TrancheBook(List<TrancheEntry> entries, List<AssetTrancheSummary> summary) {
        public TrancheBook {
            entries = List.copyOf(entries);
            summary = List.copyOf(summary);
        }
    }

    record TradeLogWriteResult(Boolean againstSystemRecommendation) {
    }
}
