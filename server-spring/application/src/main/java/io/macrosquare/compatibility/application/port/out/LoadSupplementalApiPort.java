package io.macrosquare.compatibility.application.port.out;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;

import java.util.List;

public interface LoadSupplementalApiPort {

    Document loadSmartMoney();

    Document loadSectorBacktest(int years);

    Document loadBottleneckThemes();

    Document loadBottleneckTheme(String id);

    Document loadCompanies(String sort, String query, String themeId, String sectorId, int page, int pageSize);

    Document loadHighlights();

    Document loadEarnings();

    Document loadCorrelation(int lookback, List<String> keys);

    Document loadDomesticReports();

    Document loadWeeklyReportJson();

    TextPayload loadWeeklyReportText();

    Document loadBacktestSummary();

    Document loadBacktestPortfolio(int years);

    Document loadBacktestUserPlan(int years);
}
