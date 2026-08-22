package io.macrosquare.company.adapter.out.legacy;

final class LegacyCompanyProjectionFixture {

    private LegacyCompanyProjectionFixture() {}

    static final String RESEARCH_JSON = """
            {"profile":{"ticker":"NVDA","cik":"0001045810","name":"NVIDIA CORP"},
            "quote":{"symbol":"NVDA","price":173.42,"date":null},"financials":{},"score":{},
            "buyScore":{},"filings":[],"irMaterials":[],"highlights":["quality"],
            "peerGroup":"semiconductor","bottleneck":null,"narrative":null,"capitalFlow":{},
            "cashFlowQuality":{},"multipleInsight":{},"guidanceInsight":null,"timeframeView":{},
            "correctionAssessment":{},"thesisMonitor":{},"reversalConfirmation":{},"sectorContext":{},
            "verdicts":{},"bottomSignal":{},"positionSizing":{},"executionBridge":{},"peers":[]}
            """;
}
