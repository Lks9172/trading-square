package io.macrosquare.research.adapter.out.official;

import io.macrosquare.research.application.port.out.LoadOfficialSectorFundHistoryPort;
import io.macrosquare.research.domain.rotation.SectorFundHistoryPoint;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Official State Street sector-ETF NAV/shares history adapter. */
public final class StateStreetSectorFundHistoryAdapter implements LoadOfficialSectorFundHistoryPort {

    private final RestClient restClient;
    private final URI baseUri;
    private final long maximumWorkbookBytes;
    private final StateStreetFundHistoryWorkbookParser parser = new StateStreetFundHistoryWorkbookParser();

    public StateStreetSectorFundHistoryAdapter(RestClient restClient, URI baseUri, long maximumWorkbookBytes) {
        this.restClient = Objects.requireNonNull(restClient);
        this.baseUri = Objects.requireNonNull(baseUri);
        if (!baseUri.isAbsolute()) throw new IllegalArgumentException("baseUri must be absolute");
        if (maximumWorkbookBytes < 1024 || maximumWorkbookBytes > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("maximumWorkbookBytes is outside the safe range");
        }
        this.maximumWorkbookBytes = maximumWorkbookBytes;
    }

    @Override
    public List<SectorFundHistoryPoint> load(String fundTicker) {
        var ticker = normalizeTicker(fundTicker);
        try {
            var bytes = restClient.get().uri(uri(ticker)).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("State Street returned " + response.getStatusCode().value());
                }
                return bounded(response.getBody(), maximumWorkbookBytes);
            });
            return parser.parse(bytes, ticker);
        } catch (RestClientException error) {
            throw new IllegalStateException("official sector fund history is unavailable", error);
        }
    }

    private URI uri(String ticker) {
        var base = baseUri.toString();
        if (!base.endsWith("/")) base += "/";
        return URI.create(base + "navhist-us-en-" + ticker.toLowerCase(Locale.ROOT) + ".xlsx");
    }

    private static String normalizeTicker(String raw) {
        if (raw == null) throw new IllegalArgumentException("fund ticker is required");
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("XL[A-Z]{1,2}")) throw new IllegalArgumentException("unsupported sector fund ticker");
        return value;
    }

    private static byte[] bounded(InputStream input, long maximumBytes) throws IOException {
        try (input) {
            var bytes = input.readNBytes(Math.toIntExact(maximumBytes + 1));
            if (bytes.length > maximumBytes) throw new IllegalArgumentException("fund workbook exceeded byte limit");
            return bytes;
        }
    }
}
