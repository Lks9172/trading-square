package io.macrosquare.compatibility.application.port.in;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;

import java.util.List;

public interface QuerySupplementalApiUseCase {

    Document smartMoney();

    Document sectorBacktest(String years);

    Document bottleneckThemes();

    Document bottleneckTheme(String id);

    Document companies(String sort, String query, String themeId, String sectorId, String page, String pageSize);

    Document highlights();

    Document earnings();

    Document correlation(String lookback, List<String> keyParameters);

    Document domesticReports();

    Document weeklyReportJson();

    TextPayload weeklyReportText();

    Document backtestSummary();

    Document backtestPortfolio(String years);

    Document backtestUserPlan(String years);
}
