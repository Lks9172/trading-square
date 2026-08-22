package io.macrosquare.institutional.adapter.out.sec;

import io.macrosquare.institutional.application.port.out.CollectInstitutionalFilingsPort;
import io.macrosquare.institutional.application.port.out.InstitutionalCollectionException;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Official SEC submissions + EDGAR archive collector for the latest 13F quarters. */
public final class Sec13fInstitutionalFilingAdapter implements CollectInstitutionalFilingsPort {

    private final RestClient dataClient;
    private final RestClient archiveClient;
    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final Duration interRequestDelay;
    private final long maximumIndexBytes;
    private final long maximumInformationTableBytes;
    private final AtomicLong nextRequestNanos = new AtomicLong();

    public Sec13fInstitutionalFilingAdapter(
            RestClient dataClient,
            RestClient archiveClient,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            Clock clock,
            Duration interRequestDelay,
            long maximumIndexBytes,
            long maximumInformationTableBytes
    ) {
        this.dataClient = Objects.requireNonNull(dataClient);
        this.archiveClient = Objects.requireNonNull(archiveClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.objectStorage = objectStorage;
        this.clock = Objects.requireNonNull(clock);
        this.interRequestDelay = Objects.requireNonNull(interRequestDelay);
        if (interRequestDelay.isNegative()) throw new IllegalArgumentException("interRequestDelay must not be negative");
        if (maximumIndexBytes <= 0 || maximumInformationTableBytes <= 0) {
            throw new IllegalArgumentException("SEC 13F byte limits must be positive");
        }
        this.maximumIndexBytes = maximumIndexBytes;
        this.maximumInformationTableBytes = maximumInformationTableBytes;
    }

    @Override
    public List<InstitutionalFiling> collect(InstitutionalManager manager, int filingLimit) {
        Objects.requireNonNull(manager);
        if (filingLimit < 2 || filingLimit > 8) throw new IllegalArgumentException("filingLimit must be between 2 and 8");
        try {
            var references = submissions(manager, filingLimit);
            var result = new ArrayList<InstitutionalFiling>();
            for (var reference : references) result.add(filing(manager, reference));
            return List.copyOf(result);
        } catch (InstitutionalCollectionException error) {
            throw error;
        } catch (RestClientException | JacksonException | IllegalArgumentException error) {
            throw new InstitutionalCollectionException("Unable to collect SEC 13F filings for " + manager.id(), error);
        }
    }

    private List<Sec13fSubmissionsParser.FilingReference> submissions(
            InstitutionalManager manager,
            int filingLimit
    ) {
        pace();
        var result = dataClient.get()
                .uri("/submissions/CIK{cik}.json", manager.cik())
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                    try (var parser = objectMapper.createParser(response.getBody())) {
                        return Sec13fSubmissionsParser.parse(parser, manager.cik(), filingLimit);
                    }
                });
        if (result == null || result.isEmpty()) {
            throw new InstitutionalCollectionException("SEC submissions contained no 13F-HR filings for " + manager.id());
        }
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (result.stream().anyMatch(reference -> reference.filedOn().isAfter(today.plusDays(1))
                || reference.reportPeriod().isAfter(today.plusDays(1))
                || reference.reportPeriod().isAfter(reference.filedOn()))) {
            throw new InstitutionalCollectionException(
                    "SEC submissions contained an invalid or future 13F date for " + manager.id());
        }
        return result;
    }

    private InstitutionalFiling filing(
            InstitutionalManager manager,
            Sec13fSubmissionsParser.FilingReference reference
    ) {
        var cikPath = Long.toString(Long.parseLong(manager.cik()));
        var accessionPath = reference.accessionNumber().replace("-", "");
        var directory = "/Archives/edgar/data/" + cikPath + "/" + accessionPath + "/";
        var indexBytes = fetchArchive(directory + "index.json", maximumIndexBytes, MediaType.APPLICATION_JSON);
        final List<String> candidates;
        try (var parser = objectMapper.createParser(indexBytes)) {
            candidates = Sec13fIndexParser.xmlCandidates(parser);
        }
        RuntimeException lastFailure = null;
        for (var candidate : candidates) {
            if (!candidate.matches("[A-Za-z0-9._-]+")) continue;
            try {
                var xml = fetchArchive(directory + candidate, maximumInformationTableBytes, MediaType.APPLICATION_XML);
                var holdings = Sec13fInformationTableParser.parse(xml);
                if (holdings.isEmpty()) continue;
                var sourceUrl = "https://www.sec.gov" + directory + candidate;
                var rawKey = archive(manager, reference, candidate, sourceUrl, xml);
                return new InstitutionalFiling(
                        manager,
                        reference.accessionNumber(),
                        reference.filedOn(),
                        reference.reportPeriod(),
                        sourceUrl,
                        rawKey,
                        holdings
                );
            } catch (RuntimeException error) {
                lastFailure = error;
            }
        }
        throw new InstitutionalCollectionException(
                "SEC filing did not contain a usable 13F information table: " + reference.accessionNumber(),
                lastFailure
        );
    }

    private byte[] fetchArchive(String path, long maximumBytes, MediaType accept) {
        pace();
        return archiveClient.get().uri(path).accept(accept).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
            try {
                return bounded(response.getBody(), maximumBytes);
            } catch (IOException error) {
                throw new InstitutionalCollectionException("Unable to read SEC archive response", error);
            }
        });
    }

    private String archive(
            InstitutionalManager manager,
            Sec13fSubmissionsParser.FilingReference reference,
            String fileName,
            String sourceUrl,
            byte[] content
    ) {
        if (objectStorage == null) return "";
        var key = "sec-filings/13f/" + manager.cik() + "/"
                + reference.accessionNumber().replace("-", "") + "/" + fileName;
        objectStorage.put(
                key,
                content,
                MediaType.APPLICATION_XML_VALUE,
                Map.of(
                        "source", "sec-13f",
                        "manager", manager.id(),
                        "accession", reference.accessionNumber(),
                        "source-url", sourceUrl,
                        "collected-at", clock.instant().toString()
                )
        );
        return key;
    }

    private void pace() {
        var delay = interRequestDelay.toNanos();
        if (delay <= 0) return;
        while (true) {
            var observed = nextRequestNanos.get();
            var now = System.nanoTime();
            var reserved = Math.max(now, observed);
            if (!nextRequestNanos.compareAndSet(observed, reserved + delay)) continue;
            var wait = reserved - now;
            if (wait <= 0) return;
            try {
                Thread.sleep(Duration.ofNanos(wait));
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InstitutionalCollectionException("Interrupted while pacing SEC requests", error);
            }
        }
    }

    private static byte[] bounded(InputStream input, long maximumBytes) throws IOException {
        if (input == null) throw new IOException("SEC response body was empty");
        if (maximumBytes >= Integer.MAX_VALUE) throw new IllegalArgumentException("maximumBytes is too large");
        var bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("SEC response exceeded configured byte limit");
        return bytes;
    }
}
