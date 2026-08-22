package io.macrosquare.company.domain.investment;

import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;

import java.util.Objects;

/**
 * Caps a preliminary score action with fresh price-structure evidence.
 *
 * <p>This is intentionally a one-way guard: price structure may delay or reduce an entry,
 * but it can never upgrade a weak fundamental/valuation action into a buy.</p>
 */
public final class CompanyPriceStructureActionGuard {

    public Assessment evaluate(CompanyInvestmentAction preliminary, PriceStructureAnalysis structure) {
        Objects.requireNonNull(preliminary, "preliminary");
        Objects.requireNonNull(structure, "structure");
        if (rank(preliminary) <= rank(CompanyInvestmentAction.HOLD)) {
            return new Assessment(preliminary, "");
        }
        if (structure.trendState() == PriceStructureAnalysis.TrendState.UNAVAILABLE
                || structure.priceLocation() == PriceStructureAnalysis.PriceLocation.UNAVAILABLE) {
            return capped(preliminary, CompanyInvestmentAction.HOLD,
                    "가격 구조 데이터가 부족해 점수만으로 신규 매수를 허용하지 않습니다.");
        }
        if (structure.fibonacci().zoneState()
                == FibonacciRetracementAnalysis.ZoneState.LAST_DEFENSE_BROKEN) {
            return capped(preliminary, CompanyInvestmentAction.HOLD,
                    "주요 상승 파동의 0.786 기준 이탈 후 구조 회복 확인이 우선입니다.");
        }
        if (structure.priceLocation() == PriceStructureAnalysis.PriceLocation.BREAKDOWN) {
            return capped(preliminary, CompanyInvestmentAction.HOLD,
                    "지지·채널 하단 이탈 상태라 재진입 확인 전 매수를 보류합니다.");
        }
        if (structure.bearishReversalStage()
                == PriceStructureAnalysis.BearishReversalStage.PRIOR_LOW_BROKEN) {
            if (!structure.stopHuntReclaim()) {
                return capped(preliminary, CompanyInvestmentAction.REDUCE,
                        "직전 스윙 저점 이탈 뒤 재진입이 없어 기존 상승 가설이 훼손됐습니다.");
            }
            if (structure.score() < 45
                    || structure.recoveryStage() == PriceStructureAnalysis.RecoveryStage.NONE
                    || structure.recoveryStage() == PriceStructureAnalysis.RecoveryStage.BASE_BUILDING) {
                return capped(preliminary, CompanyInvestmentAction.HOLD,
                        "스톱헌트 재진입은 보이지만 구조 점수와 회복 단계가 아직 약합니다.");
            }
            return capped(preliminary, CompanyInvestmentAction.BUY,
                    "직전 저점 이탈 이력이 있어 구조 재진입이 확인돼도 적극 매수는 제한합니다.");
        }
        if (structure.bearishReversalStage()
                == PriceStructureAnalysis.BearishReversalStage.STRUCTURAL_CRACK
                && structure.recoveryStage() != PriceStructureAnalysis.RecoveryStage.STRUCTURE_BREAK
                && structure.recoveryStage() != PriceStructureAnalysis.RecoveryStage.RETEST_HELD) {
            return capped(preliminary, CompanyInvestmentAction.HOLD,
                    "낮아진 고점 이후 직전 고점 돌파가 없어 반등을 추세 전환으로 확정하지 않습니다.");
        }
        if (structure.score() < 40) {
            return capped(preliminary, CompanyInvestmentAction.HOLD,
                    "가격 구조 합치도가 40 미만이라 매수 타이밍 근거가 부족합니다.");
        }
        if (structure.priceLocation() == PriceStructureAnalysis.PriceLocation.UPPER_CHANNEL
                || structure.priceLocation() == PriceStructureAnalysis.PriceLocation.RESISTANCE_ZONE) {
            return capped(preliminary, CompanyInvestmentAction.BUY,
                    "채널 상단·저항 구간에서는 적극 추격 매수를 제한합니다.");
        }
        return new Assessment(preliminary, "");
    }

    private static Assessment capped(
            CompanyInvestmentAction preliminary,
            CompanyInvestmentAction maximum,
            String reason
    ) {
        return new Assessment(rank(preliminary) > rank(maximum) ? maximum : preliminary, reason);
    }

    private static int rank(CompanyInvestmentAction action) {
        return switch (action) {
            case SELL -> 0;
            case REDUCE -> 1;
            case HOLD -> 2;
            case BUY -> 3;
            case STRONG_BUY -> 4;
        };
    }

    public record Assessment(CompanyInvestmentAction action, String reason) {
        public Assessment {
            Objects.requireNonNull(action, "action");
            reason = reason == null ? "" : reason;
        }
    }
}
