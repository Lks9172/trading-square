package io.macrosquare.market.domain.correlation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MarketCorrelationResult(
        int lookbackDays,
        List<String> assets,
        List<List<Double>> matrix,
        List<String> missing,
        LocalDate asOf
) {
    public MarketCorrelationResult {
        if (lookbackDays < 10 || lookbackDays > 500) throw new IllegalArgumentException("lookbackDays is out of range");
        assets = List.copyOf(assets);
        matrix = matrix.stream()
                .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                .toList();
        missing = List.copyOf(missing);
        int assetCount = assets.size();
        if (matrix.size() != assetCount || matrix.stream().anyMatch(row -> row.size() != assetCount)) {
            throw new IllegalArgumentException("correlation matrix dimensions are inconsistent");
        }
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
    }
}
