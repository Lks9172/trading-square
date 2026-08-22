package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.application.model.MarketNotificationSnapshot;

public interface LoadMarketNotificationPort {
    MarketNotificationSnapshot loadCurrent();

    String loadWeeklyReportText();
}
