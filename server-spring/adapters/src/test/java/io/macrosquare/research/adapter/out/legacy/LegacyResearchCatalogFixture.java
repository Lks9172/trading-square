package io.macrosquare.research.adapter.out.legacy;

final class LegacyResearchCatalogFixture {

    private LegacyResearchCatalogFixture() {}

    static final String THEMES_JSON = """
            {
              "themes": [{
                "id": "ai-semiconductors",
                "theme": "AI / 반도체",
                "description": "AI CAPEX",
                "tickers": ["NVDA", "AMD"],
                "sectorKeys": ["SECTOR_SOXX"],
                "sectorSummary": {
                  "averageBuyScore": 66,
                  "averageBottomScore": 56,
                  "averageBottomFailureRiskScore": 42,
                  "averageVolumeConfirmationScore": null,
                  "averageAppealScore": 68,
                  "averageCrowdingScore": 37,
                  "averageQualityScore": 72,
                  "averageRotationScore": 77,
                  "topSector": {
                    "key": "SECTOR_SOXX",
                    "label": "반도체",
                    "classification": "structural",
                    "momentumScore": -11.74,
                    "qualityScore": 78,
                    "policySupport": 66,
                    "structuralDemand": 76,
                    "supplyTightness": 88,
                    "marketConcentration": 88,
                    "appealScore": 76,
                    "crowdingScore": 46,
                    "buyScore": 69,
                    "buyLabel": "매수 우호",
                    "stance": "favored",
                    "rotationScore": 77,
                    "rotationState": "LEADING",
                    "rotationLabel": "Leader",
                    "rotationReasons": ["리더십 확인"],
                    "bottomState": "재시험 구간",
                    "bottomScore": 57,
                    "bottomFailureRiskScore": 42,
                    "actionLabel": "관찰 매수",
                    "failureSummary": "재시험 확인"
                  }
                }
              }]
            }
            """;

    static final String SECTORS_JSON = """
            {
              "sectors": [{
                "id": "technology",
                "label": "기술",
                "description": "소프트웨어와 IT",
                "sectorKey": "SECTOR_XLK",
                "tickers": ["MSFT", "AAPL"],
                "sectorSummary": {
                  "averageBuyScore": 64,
                  "averageBottomScore": 56,
                  "averageBottomFailureRiskScore": 47,
                  "averageVolumeConfirmationScore": 55,
                  "averageAppealScore": 52,
                  "averageCrowdingScore": 18,
                  "averageQualityScore": 62,
                  "averageRotationScore": 77,
                  "topSector": {
                    "key": "SECTOR_XLK",
                    "label": "기술",
                    "classification": "structural",
                    "momentumScore": -5.82,
                    "qualityScore": 62,
                    "policySupport": 54,
                    "structuralDemand": 68,
                    "supplyTightness": 58,
                    "marketConcentration": 72,
                    "appealScore": 52,
                    "crowdingScore": 18,
                    "buyScore": 64,
                    "buyLabel": "선별 접근",
                    "stance": "neutral",
                    "rotationScore": 77,
                    "rotationState": "LEADING",
                    "rotationLabel": "Leader",
                    "rotationReasons": ["리더십 확인"],
                    "bottomState": "바닥 시도",
                    "bottomScore": 56,
                    "bottomFailureRiskScore": 47,
                    "actionLabel": "대기",
                    "failureSummary": "초기 바닥 시도",
                    "avgVolumeConfirmationScore": 55
                  }
                },
                "rotation": {
                  "key": "SECTOR_XLK",
                  "label": "기술",
                  "classification": "structural",
                  "rotationScore": 77,
                  "macroFitScore": 75,
                  "relativeStrengthScore": 95,
                  "fundamentalScore": 65,
                  "valuationScore": 61,
                  "earningsRevisionScore": 71,
                  "flowScore": 68,
                  "crowdingReliefScore": 54,
                  "state": "LEADING",
                  "rotationLabel": "Leader",
                  "expectedLeadershipWindow": "now",
                  "expectedLeadershipMessage": "이미 주도 구간",
                  "reasons": ["리더십 확인"]
                },
                "densitySummary": {
                  "peer": 2, "peerPct": 100,
                  "narrative": 2, "narrativePct": 100,
                  "fallback": 1, "fallbackPct": 50,
                  "bottleneck": 1, "bottleneckPct": 50,
                  "capitalFlow": 2, "capitalFlowPct": 100
                },
                "relatedThemes": [{"id": "ai-semiconductors", "theme": "AI / 반도체"}]
              }],
              "rotation": {
                "regime": "RE_ACCELERATION",
                "confidence": 69,
                "summary": "재가속 단계",
                "favoredNext": ["기술"],
                "fadingNext": ["에너지"],
                "currentLeaders": [{
                  "label": "기술", "sectorKey": "SECTOR_XLK", "rotationScore": 77,
                  "state": "LEADING", "rotationLabel": "Leader",
                  "expectedLeadershipWindow": "now", "expectedLeadershipMessage": "이미 주도 구간",
                  "note": "리더십 확인"
                }],
                "nextCandidates": [{
                  "label": "인프라", "sectorKey": "SECTOR_IGF", "rotationScore": 75,
                  "state": "IMPROVING", "rotationLabel": "Rotation In",
                  "expectedLeadershipWindow": "1_3m", "expectedLeadershipMessage": "1~3개월 후보",
                  "note": "개선 중"
                }],
                "secondaryCandidates": [],
                "fadingCandidates": []
              }
            }
            """;

    private static final String DETAIL_SCORE = """
            {
              "key": "SECTOR_XLK",
              "label": "기술",
              "classification": "structural",
              "momentumScore": -5.82,
              "qualityScore": 62,
              "policySupport": 54,
              "structuralDemand": 68,
              "supplyTightness": 58,
              "marketConcentration": 72,
              "appealScore": 52,
              "crowdingScore": 18,
              "buyScore": 64,
              "buyLabel": "선별 접근",
              "stance": "neutral",
              "rotationScore": 77,
              "rotationState": "LEADING",
              "rotationLabel": "Leader",
              "rotationReasons": ["리더십 확인"],
              "bottomState": "바닥 시도",
              "bottomScore": 56,
              "bottomFailureRiskScore": 47,
              "actionLabel": "대기",
              "failureSummary": "초기 바닥 시도",
              "buyScoreDelta7d": null,
              "buyScoreDelta30d": null,
              "buyScoreTrend": [null, null, 64]
            }
            """;

    private static final String ENRICHED_DETAIL_SCORE = DETAIL_SCORE.replace(
            "\"buyScoreDelta7d\": null",
            "\"avgVolumeConfirmationScore\": 55,\n  \"buyScoreDelta7d\": null"
    );

    private static final String COMPANY_ITEM = """
            {
              "ticker": "NVDA",
              "name": "NVIDIA",
              "marketCap": 1000000000,
              "totalScore": 75,
              "buyScore": 72,
              "buyLabel": "BUY",
              "appealScore": 70,
              "crowdingScore": 28,
              "revenueGrowthYoY": 12.4,
              "operatingMargin": null,
              "evToSales": 8.2,
              "sectorKey": "SECTOR_XLK",
              "bottomScore": 66,
              "priceBottomScore": 64,
              "volumeConfirmationScore": 70,
              "failureRiskScore": 30,
              "bottomState": "1차 확인",
              "confirmedBottomScore": 76,
              "confirmedBottomState": "후보",
              "rank": 1
            }
            """;

    static final String THEME_DETAIL_JSON = """
            {
              "theme": {
                "id": "ai-semiconductors",
                "theme": "AI / 반도체",
                "description": "AI CAPEX",
                "tickers": ["NVDA"],
                "sectorKeys": ["SECTOR_XLK"]
              },
              "items": [%s],
              "sectorScores": [%s],
              "sectorSummary": {
                "averageBuyScore": 64,
                "averageBottomScore": 56,
                "averageBottomFailureRiskScore": 47,
                "averageVolumeConfirmationScore": null,
                "averageAppealScore": 52,
                "averageCrowdingScore": 18,
                "averageQualityScore": 62,
                "averageRotationScore": 77,
                "topSector": %s
              },
              "sortKey": "quality",
              "companySortKey": "marketcap"
            }
            """.formatted(COMPANY_ITEM, DETAIL_SCORE, DETAIL_SCORE);

    static final String SECTOR_DETAIL_JSON = """
            {
              "sector": {
                "id": "technology",
                "label": "기술",
                "description": "소프트웨어와 IT",
                "sectorKey": "SECTOR_XLK",
                "tickers": ["MSFT"]
              },
              "sortKey": "priority",
              "relatedThemes": [],
              "sectorScores": [%s],
              "sectorSummary": {
                "averageBuyScore": 64,
                "averageBottomScore": 56,
                "averageBottomFailureRiskScore": 47,
                "averageVolumeConfirmationScore": 55,
                "averageAppealScore": 52,
                "averageCrowdingScore": 18,
                "averageQualityScore": 62,
                "averageRotationScore": 77,
                "topSector": %s
              },
              "rotation": null,
              "rotationSummary": null,
              "densitySummary": {
                "peer": 1, "peerPct": 100,
                "narrative": 1, "narrativePct": 100,
                "fallback": 1, "fallbackPct": 100,
                "bottleneck": 1, "bottleneckPct": 100,
                "capitalFlow": 1, "capitalFlowPct": 100
              },
              "items": []
            }
            """.formatted(ENRICHED_DETAIL_SCORE, ENRICHED_DETAIL_SCORE);
}
