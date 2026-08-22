package io.macrosquare.execution.application.port.out;

import io.macrosquare.execution.application.model.WeeklyReviewMarketContext;

public interface LoadWeeklyReviewMarketContextPort {
    WeeklyReviewMarketContext loadCurrent();
}
