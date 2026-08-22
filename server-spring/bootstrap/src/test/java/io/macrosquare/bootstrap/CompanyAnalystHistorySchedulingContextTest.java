package io.macrosquare.bootstrap;

import io.macrosquare.company.adapter.in.scheduling.CompanyAnalystHistoryScheduler;
import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.bootstrap.config.CompanyAnalystHistoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "macrosquare.company-analyst-history.enabled=true",
        "macrosquare.company-analyst-history.read-mode=dual-compare",
        "macrosquare.company-analyst-history.directory=/tmp/macrosquare-analyst-history-scheduling-context",
        "macrosquare.company-analyst-history.startup-delay=1h",
        "macrosquare.company-analyst-history.weekday-cron=-",
        "macrosquare.company-analyst-history.weekend-cron=-"
})
class CompanyAnalystHistorySchedulingContextTest {

    @Autowired
    CompanyAnalystHistoryScheduler scheduler;

    @Autowired
    CompanyAnalystHistoryProperties properties;

    @Test
    void enablesTheFeatureFlaggedSchedulerWithDurationAndDisabledCronPlaceholders() {
        assertNotNull(scheduler);
        org.junit.jupiter.api.Assertions.assertEquals(
                CompanyAnalystHistoryRead.Mode.DUAL_COMPARE,
                properties.readMode()
        );
    }
}
