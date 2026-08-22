package io.macrosquare.research.adapter.out.legacy;

final class LegacyResearchSnapshotFixture {

    private LegacyResearchSnapshotFixture() {}

    static final String SNAPSHOT_JSON = """
            {
              "timestamp": "2026-07-19T00:00:00.000Z",
              "raw": {
                "WTI": {"value": 81.78},
                "DXY": {"value": 100.755},
                "T10Y2Y": {"value": 0.37},
                "STLFSI4": {"value": -0.882},
                "BAMLH0A0HYM2": {"value": 2.71},
                "VIXCLS": {"value": 20.0}
              },
              "derived": {
                "LIQUIDITY_DIRECTION": {"value": 4},
                "REAL_YIELD": {"value": 2.33},
                "OVERHEATED": {"value": 0},
                "COPPER_GOLD_RATIO_UPTURN": {"value": 1},
                "CREDIT_HY_OAS_BP": {"value": 271}
              },
              "regime": {"regime": "NEUTRAL"},
              "signals": [
                {"asset": "NASDAQ", "signal": "BUY"},
                {"asset": "GOLD", "signal": "BUY"}
              ],
              "allocation": {},
              "meta": {
                "profile": {"manualInputs": {"geoRisk": 3, "aiNarrativeStrength": 2}},
                "narratives": [
                  {
                    "theme": {
                      "id": "ai-power",
                      "title": "AI / 반도체",
                      "description": "AI CAPEX, 반도체, 데이터센터 전력 수요가 함께 강화되는 국면 추적",
                      "proxies": ["SECTOR_SOXX", "SECTOR_GRID", "SECTOR_IGF", "NASDAQ_SIGNAL", "NASDAQ_DISPARITY"],
                      "externalQueries": {
                        "youtubeQuery": "AI infrastructure semiconductor datacenter power",
                        "newsQuery": "AI infrastructure semiconductor datacenter power"
                      }
                    },
                    "generatedAt": "2026-07-19T00:00:00.000Z",
                    "stage": "MID",
                    "heatScore": 46,
                    "drivers": ["NASDAQ BUY"],
                    "risks": ["YouTube Search 과열 8680"],
                    "proxyScores": [
                      {"key": "NASDAQ_SIGNAL", "label": "NASDAQ 신호", "score": 7, "detail": "NASDAQ BUY"}
                    ],
                    "externalSignals": [
                      {"key": "YOUTUBE_30D", "label": "YouTube Search", "value": 8680, "score": 9, "detail": "검색 추정 8680건"}
                    ],
                    "trend": "STABLE",
                    "heatDelta7d": 1,
                    "heatDelta30d": null,
                    "heatHistory": [
                      {"date": "2026-07-18", "heatScore": 45},
                      {"date": "2026-07-19", "heatScore": 46}
                    ]
                  }
                ],
                "topdown": {
                  "rotation": {
                    "regime": "RE_ACCELERATION",
                    "confidence": 69,
                    "regimeScores": {
                      "EARLY_CYCLICAL": 78,
                      "MID_GROWTH": 81,
                      "LATE_INFLATION": 32,
                      "DEFENSIVE": 11,
                      "RE_ACCELERATION": 100
                    }
                  }
                }
              }
            }
            """;
}
