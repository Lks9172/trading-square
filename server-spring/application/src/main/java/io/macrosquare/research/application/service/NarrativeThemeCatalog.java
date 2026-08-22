package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.NarrativeExternalQueries;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class NarrativeThemeCatalog {

    private final List<NarrativeThemeDefinition> definitions;
    private final Map<NarrativeTheme, NarrativeThemeDefinition> byTheme;

    public NarrativeThemeCatalog() {
        definitions = List.of(
                definition(
                        NarrativeTheme.AI_POWER,
                        "AI / 반도체",
                        "AI CAPEX, 반도체, 데이터센터 전력 수요가 함께 강화되는 국면 추적",
                        List.of("SECTOR_SOXX", "SECTOR_GRID", "SECTOR_IGF", "NASDAQ_SIGNAL", "NASDAQ_DISPARITY"),
                        "AI infrastructure semiconductor datacenter power"
                ),
                definition(
                        NarrativeTheme.GRID_CAPEX,
                        "전력망 / 인프라",
                        "전력망, 냉각, EPC, 전력장비 CAPEX 내러티브의 확산 정도 측정",
                        List.of("SECTOR_GRID", "SECTOR_IGF", "SECTOR_XLU", "COPPER", "AI_NARRATIVE_STRENGTH"),
                        "grid capex power infrastructure transformer cooling data center"
                ),
                definition(
                        NarrativeTheme.DEFENSE_REARM,
                        "방산 / 재무장",
                        "지정학 리스크와 국방예산 확대에 따른 방산 내러티브의 과열/확산 측정",
                        List.of("SECTOR_ITA", "GEO_RISK", "WTI", "GOLD_SIGNAL"),
                        "defense rearmament missile drone aerospace"
                ),
                definition(
                        NarrativeTheme.FINANCE_LIQUIDITY,
                        "금융 / 유동성",
                        "금융, 거래소, 결제 레일의 유동성 확대/긴축 민감도를 추적",
                        List.of("SECTOR_XLF", "VIXCLS", "NASDAQ_SIGNAL"),
                        "bank liquidity capital markets payment rails"
                ),
                definition(
                        NarrativeTheme.ENERGY_SUPPLY,
                        "에너지 / 공급",
                        "원유, 정유, 오일서비스, 가스 파이프라인 공급 내러티브 추적",
                        List.of("SECTOR_XLE", "WTI", "COPPER"),
                        "energy supply oil services refining pipeline"
                ),
                definition(
                        NarrativeTheme.DIGITAL_ATTENTION,
                        "디지털 플랫폼 / 미디어",
                        "광고, 스트리밍, 통신·미디어 플랫폼의 관심도와 확산 속도를 추적",
                        List.of("SECTOR_XLC", "NASDAQ_SIGNAL", "AI_NARRATIVE_STRENGTH"),
                        "digital advertising streaming telecom platform media"
                ),
                definition(
                        NarrativeTheme.CONSUMER_DEMAND,
                        "소비 / 수요",
                        "임의소비재와 외식·여행·유통 수요 회복의 강도를 추적",
                        List.of("SECTOR_XLY", "SECTOR_XLP", "COPPER"),
                        "consumer demand travel retail restaurant spending"
                ),
                definition(
                        NarrativeTheme.CONSUMER_DEFENSIVE,
                        "소비 / 방어",
                        "소비 강도와 필수소비재 방어 수요의 균형을 추적",
                        List.of("SECTOR_XLY", "SECTOR_XLP", "SECTOR_XLV"),
                        "consumer spending staples defensive retail quality"
                ),
                definition(
                        NarrativeTheme.MATERIALS_REFLATION,
                        "소재 / 리플레이션",
                        "산업금속, 화학, 자본재 수요가 동반되는 리플레이션 내러티브 추적",
                        List.of("SECTOR_XLB", "COPPER", "WTI"),
                        "materials reflation copper chemicals industrial metals"
                ),
                definition(
                        NarrativeTheme.REAL_ASSETS_RATE,
                        "부동산 / 실물자산",
                        "리츠와 인프라 실물자산이 금리 환경에서 어떻게 반응하는지 추적",
                        List.of("SECTOR_XLRE", "SECTOR_IGF", "GOLD_SIGNAL"),
                        "reit data center tower infrastructure rate sensitivity"
                ),
                definition(
                        NarrativeTheme.SAFEHAVEN_GOLD,
                        "금 / 안전자산",
                        "실질금리, 달러, 변동성, 중앙은행 수요를 반영한 금 내러티브 강도 측정",
                        List.of("GOLD_SIGNAL", "GOLD_PRIORITY_SCORE", "VIXCLS", "GOLD_DISPARITY", "CB_GOLD_STRUCTURAL_DEMAND"),
                        "gold safe haven central bank buying real yield"
                )
        );
        var indexed = new EnumMap<NarrativeTheme, NarrativeThemeDefinition>(NarrativeTheme.class);
        definitions.forEach(definition -> indexed.put(definition.theme(), definition));
        byTheme = Map.copyOf(indexed);
    }

    public List<NarrativeThemeDefinition> definitions() {
        return definitions;
    }

    public NarrativeThemeDefinition definition(NarrativeTheme theme) {
        var definition = byTheme.get(theme);
        if (definition == null) throw new IllegalArgumentException("Unknown narrative theme: " + theme);
        return definition;
    }

    private static NarrativeThemeDefinition definition(
            NarrativeTheme theme,
            String title,
            String description,
            List<String> proxies,
            String externalQuery
    ) {
        return new NarrativeThemeDefinition(
                theme,
                title,
                description,
                proxies,
                new NarrativeExternalQueries(externalQuery, externalQuery)
        );
    }
}
