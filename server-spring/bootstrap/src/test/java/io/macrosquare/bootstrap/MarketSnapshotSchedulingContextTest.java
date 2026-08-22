package io.macrosquare.bootstrap;

import io.macrosquare.market.adapter.in.scheduling.MarketSnapshotRefreshScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "macrosquare.market-collection.enabled=false",
        "macrosquare.market-collection.snapshot-refresh-enabled=true",
        "macrosquare.market-collection.snapshot-startup-delay=1h"
})
class MarketSnapshotSchedulingContextTest {

    @Autowired
    MarketSnapshotRefreshScheduler snapshotScheduler;

    @Autowired
    @Qualifier("marketObservationTaskScheduler")
    ThreadPoolTaskScheduler taskScheduler;

    @Test
    void snapshotRefreshCanBeEnabledWithoutNetworkCollectors() {
        assertNotNull(snapshotScheduler);
        assertNotNull(taskScheduler);
    }
}
