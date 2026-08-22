package io.macrosquare.institutional.adapter.in.web;

import io.macrosquare.institutional.domain.model.InstitutionalFlowSnapshot;
import io.macrosquare.institutional.domain.model.InstitutionalManagerFlow;
import io.macrosquare.institutional.domain.model.InstitutionalPositionFlow;

import java.time.LocalDate;
import java.util.List;

public record InstitutionalFlowResponse(
        String status,
        LocalDate asOf,
        String source,
        int managerCount,
        int sharedPositionCount,
        int mappedPositionCount,
        int unmappedPositionCount,
        List<ManagerResponse> managers,
        List<ConsensusResponse> consensus,
        List<DivergenceResponse> divergences,
        String methodology
) {
    static InstitutionalFlowResponse from(InstitutionalFlowSnapshot value) {
        return new InstitutionalFlowResponse(
                value.managerCount() == 0 ? "collecting" : "ready",
                value.asOf(),
                value.source(),
                value.managerCount(),
                value.sharedPositionCount(),
                value.mappedPositionCount(),
                value.unmappedPositionCount(),
                value.managers().stream().map(ManagerResponse::from).toList(),
                value.consensus().stream().map(item -> new ConsensusResponse(
                        item.cusip(), item.issuer(), item.titleClass(), item.managerCount(), item.managers(),
                        item.totalValueUsd(), item.netValueDeltaUsd(), item.estimatedNetFlowUsd(),
                        IdentityResponse.from(item.identity()))).toList(),
                value.divergences().stream().map(item -> new DivergenceResponse(
                        item.ticker(), item.issuer(), item.sectorKey(), item.analystScore(),
                        item.institutionalFlowScore(), item.divergenceScore(), item.managerCount(),
                        item.aggregateShareDeltaPct(), item.signal())).toList(),
                "SEC 13F-HR 최신 두 보고기간의 보고 주식수를 비교합니다. CUSIP은 SEC issuer directory와 보수적 명칭 매칭으로 point-in-time 관리하며 애매한 종목은 미매핑으로 남깁니다. 괴리는 현재 애널리스트 의견과 지연 공시된 실제 수량 방향의 차이이며 단독 매매 신호가 아닙니다."
        );
    }

    public record ManagerResponse(
            String id,
            String name,
            String cik,
            LocalDate reportPeriod,
            LocalDate previousReportPeriod,
            LocalDate filedOn,
            String sourceUrl,
            int holdingCount,
            double totalValueUsd,
            double netValueDeltaUsd,
            double estimatedNetFlowUsd,
            int newPositions,
            int increasedPositions,
            int reducedPositions,
            int exitedPositions,
            List<PositionResponse> topBuys,
            List<PositionResponse> topSells
    ) {
        static ManagerResponse from(InstitutionalManagerFlow value) {
            return new ManagerResponse(
                    value.manager().id(), value.manager().name(), value.manager().cik(),
                    value.reportPeriod(), value.previousReportPeriod(), value.filedOn(), value.sourceUrl(),
                    value.holdingCount(), value.totalValueUsd(), value.netValueDeltaUsd(),
                    value.estimatedNetFlowUsd(),
                    value.newPositions(), value.increasedPositions(), value.reducedPositions(),
                    value.exitedPositions(),
                    value.topBuys().stream().map(PositionResponse::from).toList(),
                    value.topSells().stream().map(PositionResponse::from).toList()
            );
        }
    }

    public record PositionResponse(
            String cusip,
            String issuer,
            String titleClass,
            String putCall,
            double currentValueUsd,
            double valueDeltaUsd,
            Double valueDeltaPct,
            double shareDelta,
            Double shareDeltaPct,
            double estimatedNetFlowUsd,
            String action,
            IdentityResponse identity
    ) {
        static PositionResponse from(InstitutionalPositionFlow value) {
            return new PositionResponse(
                    value.cusip(), value.issuer(), value.titleClass(), value.putCall(),
                    value.currentValueUsd(), value.valueDeltaUsd(), value.valueDeltaPct(),
                    value.shareDelta(), value.shareDeltaPct(), value.estimatedNetFlowUsd(),
                    value.action().name(), IdentityResponse.from(value.identity()));
        }
    }

    public record ConsensusResponse(
            String cusip,
            String issuer,
            String titleClass,
            int managerCount,
            List<String> managers,
            double totalValueUsd,
            double netValueDeltaUsd,
            double estimatedNetFlowUsd,
            IdentityResponse identity
    ) {
    }

    public record IdentityResponse(
            String ticker,
            String cik,
            String sectorKey,
            int confidence,
            String source
    ) {
        static IdentityResponse from(
                io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity value
        ) {
            return value == null ? null : new IdentityResponse(
                    value.ticker(), value.cik(), value.sectorKey(), value.confidence(), value.source());
        }
    }

    public record DivergenceResponse(
            String ticker,
            String issuer,
            String sectorKey,
            double analystScore,
            double institutionalFlowScore,
            double divergenceScore,
            int managerCount,
            double aggregateShareDeltaPct,
            String signal
    ) {
    }
}
