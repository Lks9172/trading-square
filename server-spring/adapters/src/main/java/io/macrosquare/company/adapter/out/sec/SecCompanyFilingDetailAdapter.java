package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.out.CompanyFilingDetailUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.application.port.out.CompanyRevenueMixUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDetailEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDocumentContentPort;
import io.macrosquare.company.application.port.out.LoadCompanyRevenueMixEvidencePort;
import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;
import io.macrosquare.company.domain.model.CompanyFilingDetailEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.adapter.out.storage.ObjectStorageException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Read-only, bounded EDGAR accession-index and filing-text adapter.
 */
public final class SecCompanyFilingDetailAdapter
        implements LoadCompanyFilingDetailEvidencePort,
        LoadCompanyFilingDocumentContentPort,
        LoadCompanyRevenueMixEvidencePort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecCompanyFilingDetailAdapter.class);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE = Pattern.compile("<style\\b[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);

    private final RestClient restClient;
    private final URI baseUrl;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Duration interRequestDelay;
    private final int maxIndexBytes;
    private final int maxDocumentBytes;
    private final int maxInlineXbrlBytes;
    private final int maxTextCharacters;
    private final int maxPdfPages;
    private final int maxDetailEntries;
    private final int maxTextEntries;
    private final Semaphore fetchPermits;
    private final ObjectStorage objectStorage;
    private final ConcurrentHashMap<String, CachedDetail> detailCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedContent> textCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedRevenueMix> revenueMixCache = new ConcurrentHashMap<>();
    private final ReentrantLock detailLock = new ReentrantLock(true);
    private final ReentrantLock textLock = new ReentrantLock(true);
    private final ReentrantLock revenueMixLock = new ReentrantLock(true);
    private final ReentrantLock pacingLock = new ReentrantLock(true);
    private long nextRequestNanos;

    public SecCompanyFilingDetailAdapter(
            RestClient restClient,
            URI baseUrl,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Duration interRequestDelay,
            int maxIndexBytes,
            int maxDocumentBytes,
            int maxInlineXbrlBytes,
            int maxTextCharacters,
            int maxPdfPages,
            int maxDetailEntries,
            int maxTextEntries,
            int maxConcurrentFetches
    ) {
        this(restClient, baseUrl, clock, cacheTtl, staleTtl, interRequestDelay,
                maxIndexBytes, maxDocumentBytes, maxInlineXbrlBytes, maxTextCharacters,
                maxPdfPages, maxDetailEntries, maxTextEntries, maxConcurrentFetches, null);
    }

    public SecCompanyFilingDetailAdapter(
            RestClient restClient,
            URI baseUrl,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Duration interRequestDelay,
            int maxIndexBytes,
            int maxDocumentBytes,
            int maxInlineXbrlBytes,
            int maxTextCharacters,
            int maxPdfPages,
            int maxDetailEntries,
            int maxTextEntries,
            int maxConcurrentFetches,
            ObjectStorage objectStorage
    ) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.baseUrl = requireHttpsBaseUrl(baseUrl);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheTtl = requireNonNegative(cacheTtl, "cacheTtl");
        this.staleTtl = Objects.requireNonNull(staleTtl, "staleTtl");
        if (staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        this.interRequestDelay = requireNonNegative(interRequestDelay, "interRequestDelay");
        if (maxIndexBytes < 1 || maxDocumentBytes < 1 || maxInlineXbrlBytes < 1
                || maxTextCharacters < 1 || maxPdfPages < 1
                || maxDetailEntries < 1 || maxTextEntries < 1 || maxConcurrentFetches < 1) {
            throw new IllegalArgumentException("SEC filing bounds must be positive");
        }
        this.maxIndexBytes = maxIndexBytes;
        this.maxDocumentBytes = maxDocumentBytes;
        this.maxInlineXbrlBytes = maxInlineXbrlBytes;
        this.maxTextCharacters = maxTextCharacters;
        this.maxPdfPages = maxPdfPages;
        this.maxDetailEntries = maxDetailEntries;
        this.maxTextEntries = maxTextEntries;
        this.fetchPermits = new Semaphore(maxConcurrentFetches, true);
        this.objectStorage = objectStorage;
    }

    @Override
    public CompanyFilingDetailEvidence load(String cik, String accessionNumber) {
        var normalizedCik = normalizeCik(cik);
        var normalizedAccession = normalizeAccession(accessionNumber);
        var key = normalizedCik + ":" + normalizedAccession;
        var now = clock.instant();
        var cached = detailCache.get(key);
        if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
        detailLock.lock();
        try {
            now = clock.instant();
            cached = detailCache.get(key);
            if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
            try {
                var loaded = fetchDetail(normalizedCik, normalizedAccession);
                detailCache.put(key, new CachedDetail(loaded, clock.instant()));
                evictOldestDetailEntries();
                return loaded;
            } catch (RuntimeException error) {
                if (cached != null && isUsableStale(cached.loadedAt(), now)) {
                    LOGGER.warn("Unable to refresh SEC filing index {}; retaining stale evidence", normalizedAccession);
                    return cached.value();
                }
                if (error instanceof CompanyFilingDetailUnavailableException unavailable) throw unavailable;
                throw new CompanyFilingDetailUnavailableException("Unable to load SEC filing index", error);
            }
        } finally {
            detailLock.unlock();
        }
    }

    @Override
    public CompanyFilingDocumentContent loadContent(String sourceUrl) {
        var uri = validateDocumentUri(sourceUrl);
        var format = documentFormat(uri);
        var key = uri.toASCIIString();
        var now = clock.instant();
        var cached = textCache.get(key);
        if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
        textLock.lock();
        try {
            now = clock.instant();
            cached = textCache.get(key);
            if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
            try {
                var bytes = fetchBytes(uri, maxDocumentBytes);
                var loaded = extractContent(bytes, format);
                textCache.put(key, new CachedContent(loaded, clock.instant()));
                evictOldestTextEntries();
                return loaded;
            } catch (RuntimeException error) {
                if (cached != null && isUsableStale(cached.loadedAt(), now)) {
                    LOGGER.warn("Unable to refresh SEC filing document {}; retaining stale content", uri.getPath());
                    return cached.value();
                }
                if (error instanceof CompanyFilingDocumentUnavailableException unavailable) throw unavailable;
                throw new CompanyFilingDocumentUnavailableException("Unable to load SEC filing document", error);
            }
        } finally {
            textLock.unlock();
        }
    }

    @Override
    public CompanyRevenueMixEvidence loadRevenueMix(String sourceUrl) {
        var uri = validateDocumentUri(sourceUrl);
        if (documentFormat(uri) != CompanyFilingDocumentContent.Format.HTML) {
            throw new CompanyRevenueMixUnavailableException("Revenue mix requires an Inline XBRL HTML document");
        }
        var key = uri.toASCIIString();
        var now = clock.instant();
        var cached = revenueMixCache.get(key);
        if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
        revenueMixLock.lock();
        try {
            now = clock.instant();
            cached = revenueMixCache.get(key);
            if (isFresh(cached == null ? null : cached.loadedAt(), now)) return cached.value();
            try {
                var loaded = SecInlineXbrlRevenueMixParser.parse(
                        fetchBytes(uri, maxInlineXbrlBytes), key
                );
                revenueMixCache.put(key, new CachedRevenueMix(loaded, clock.instant()));
                evictOldestRevenueMixEntries();
                return loaded;
            } catch (RuntimeException error) {
                if (cached != null && isUsableStale(cached.loadedAt(), now)) {
                    LOGGER.warn("Unable to refresh SEC Inline XBRL {}; retaining stale revenue mix", uri.getPath());
                    return cached.value();
                }
                if (error instanceof CompanyRevenueMixUnavailableException unavailable) throw unavailable;
                throw new CompanyRevenueMixUnavailableException("Unable to load SEC Inline XBRL revenue mix", error);
            }
        } finally {
            revenueMixLock.unlock();
        }
    }

    private CompanyFilingDetailEvidence fetchDetail(String cik, String accessionNumber) {
        var directory = filingDirectory(cik, accessionNumber);
        var htm = baseUrl.resolve(directory + accessionNumber + "-index.htm");
        try {
            return parseIndex(fetchBytes(htm, maxIndexBytes), cik, accessionNumber, htm);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() != 404) {
                throw new CompanyFilingDetailUnavailableException("SEC filing index request failed", error);
            }
            var html = baseUrl.resolve(directory + accessionNumber + "-index.html");
            return parseIndex(fetchBytes(html, maxIndexBytes), cik, accessionNumber, html);
        }
    }

    private CompanyFilingDetailEvidence parseIndex(
            byte[] bytes,
            String cik,
            String accessionNumber,
            URI indexUri
    ) {
        try {
            return SecFilingIndexParser.parse(
                    new String(bytes, StandardCharsets.UTF_8),
                    cik,
                    accessionNumber,
                    indexUri,
                    baseUrl
            );
        } catch (IllegalArgumentException error) {
            throw new CompanyFilingDetailUnavailableException("SEC filing index could not be normalized", error);
        }
    }

    private byte[] fetchBytes(URI uri, int maxBytes) {
        var objectKey = filingObjectKey(uri);
        if (objectStorage != null) {
            try {
                var stored = objectStorage.find(objectKey, maxBytes);
                if (stored.isPresent()) return stored.get().content();
            } catch (ObjectStorageException error) {
                LOGGER.warn("Unable to read SEC artifact from object storage {}; using origin", objectKey, error);
            }
        }
        var acquired = false;
        try {
            fetchPermits.acquire();
            acquired = true;
            paceRequests();
            var result = restClient.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                var declaredLength = response.getHeaders().getContentLength();
                if (declaredLength > maxBytes) {
                    throw new IllegalArgumentException("SEC response exceeded the configured byte limit");
                }
                try (var body = response.getBody()) {
                    return readBounded(body, maxBytes);
                }
            });
            if (result == null) throw new IllegalArgumentException("SEC response was empty");
            persistArtifact(objectKey, uri, result);
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading SEC filing data", error);
        } catch (RestClientException | IllegalArgumentException error) {
            throw error;
        } finally {
            if (acquired) fetchPermits.release();
        }
    }

    private void persistArtifact(String objectKey, URI source, byte[] content) {
        if (objectStorage == null) return;
        try {
            objectStorage.put(objectKey, content, contentType(source), Map.of(
                    "source", "sec-edgar",
                    "source-sha256", sha256(source.toASCIIString().getBytes(StandardCharsets.UTF_8))
            ));
        } catch (ObjectStorageException error) {
            // Origin evidence remains usable; the storage health/metric reports the degraded durability path.
            LOGGER.warn("Unable to persist SEC artifact in object storage {}", objectKey, error);
        }
    }

    private static String filingObjectKey(URI uri) {
        var path = uri.getPath().toLowerCase(Locale.ROOT);
        var extension = path.endsWith(".pdf") ? ".pdf"
                : path.endsWith(".xml") ? ".xml"
                : path.endsWith(".txt") ? ".txt" : ".html";
        return "sec-filings/" + sha256(uri.toASCIIString().getBytes(StandardCharsets.UTF_8)) + extension;
    }

    private static String contentType(URI uri) {
        var path = uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".pdf")) return "application/pdf";
        if (path.endsWith(".xml")) return "application/xml";
        if (path.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "text/html; charset=utf-8";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void paceRequests() throws InterruptedException {
        if (interRequestDelay.isZero()) return;
        pacingLock.lock();
        try {
            var now = System.nanoTime();
            var waitNanos = nextRequestNanos - now;
            if (waitNanos > 0) {
                var millis = waitNanos / 1_000_000L;
                var nanos = (int) (waitNanos % 1_000_000L);
                Thread.sleep(millis, nanos);
            }
            nextRequestNanos = System.nanoTime() + interRequestDelay.toNanos();
        } finally {
            pacingLock.unlock();
        }
    }

    private CompanyFilingDocumentContent extractContent(
            byte[] bytes,
            CompanyFilingDocumentContent.Format format
    ) {
        if (format == CompanyFilingDocumentContent.Format.PDF) return extractPdf(bytes);
        var normalized = format == CompanyFilingDocumentContent.Format.HTML
                ? normalizeHtml(new String(bytes, StandardCharsets.UTF_8))
                : normalizePlainText(new String(bytes, StandardCharsets.UTF_8));
        var truncated = normalized.length() > maxTextCharacters;
        var text = truncated ? normalized.substring(0, maxTextCharacters) : normalized;
        return new CompanyFilingDocumentContent(text, format, null, null, truncated);
    }

    private CompanyFilingDocumentContent extractPdf(byte[] bytes) {
        if (!hasPdfHeader(bytes)) {
            throw new CompanyFilingDocumentUnavailableException("SEC PDF document has an invalid header");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new CompanyFilingDocumentUnavailableException("SEC PDF does not permit text extraction");
            }
            var totalPages = document.getNumberOfPages();
            if (totalPages < 1) {
                throw new CompanyFilingDocumentUnavailableException("SEC PDF contains no pages");
            }
            var writer = new BoundedTextWriter(maxTextCharacters);
            var pageLimit = Math.min(totalPages, maxPdfPages);
            var processedPages = 0;
            for (var page = 1; page <= pageLimit; page++) {
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.writeText(document, writer);
                processedPages = page;
                if (writer.limitReached()) break;
            }
            var text = normalizePlainText(writer.value());
            if (text.isBlank()) {
                throw new CompanyFilingDocumentUnavailableException(
                        "SEC PDF contains no extractable text"
                );
            }
            var truncated = writer.limitReached() || processedPages < totalPages;
            return new CompanyFilingDocumentContent(
                    text,
                    CompanyFilingDocumentContent.Format.PDF,
                    totalPages,
                    processedPages,
                    truncated
            );
        } catch (CompanyFilingDocumentUnavailableException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new CompanyFilingDocumentUnavailableException("SEC PDF text extraction failed", error);
        }
    }

    private String normalizeHtml(String value) {
        var withoutScripts = SCRIPT.matcher(value).replaceAll(" ");
        var withoutStyles = STYLE.matcher(withoutScripts).replaceAll(" ");
        var withoutTags = TAG.matcher(withoutStyles).replaceAll(" ");
        return normalizePlainText(SecFilingIndexParser.decodeEntities(withoutTags));
    }

    private static String normalizePlainText(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static boolean hasPdfHeader(byte[] bytes) {
        var end = Math.min(bytes.length - 4, 1_024);
        for (var index = 0; index < end; index++) {
            if (bytes[index] == '%' && bytes[index + 1] == 'P' && bytes[index + 2] == 'D'
                    && bytes[index + 3] == 'F' && bytes[index + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    private static CompanyFilingDocumentContent.Format documentFormat(URI uri) {
        var path = uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".pdf")) return CompanyFilingDocumentContent.Format.PDF;
        if (path.endsWith(".txt")) return CompanyFilingDocumentContent.Format.TEXT;
        if (path.endsWith(".htm") || path.endsWith(".html") || path.endsWith(".xml")) {
            return CompanyFilingDocumentContent.Format.HTML;
        }
        throw new CompanyFilingDocumentUnavailableException("Unsupported SEC filing document format");
    }

    private URI validateDocumentUri(String sourceUrl) {
        final URI uri;
        try {
            uri = URI.create(sourceUrl).normalize();
        } catch (RuntimeException error) {
            throw new CompanyFilingDocumentUnavailableException("Invalid SEC filing document URL", error);
        }
        var sameOrigin = "https".equalsIgnoreCase(uri.getScheme())
                && baseUrl.getHost().equalsIgnoreCase(uri.getHost())
                && effectivePort(baseUrl) == effectivePort(uri);
        var rawPath = uri.getRawPath();
        if (!sameOrigin || uri.getUserInfo() != null || uri.getFragment() != null
                || rawPath == null || rawPath.toLowerCase(Locale.ROOT).contains("%2e")
                || !uri.getPath().startsWith("/Archives/edgar/data/")) {
            throw new CompanyFilingDocumentUnavailableException("SEC filing document URL escaped the archive origin");
        }
        return uri;
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        var output = new ByteArrayOutputStream(Math.min(maxBytes, 32_768));
        var buffer = new byte[8_192];
        var total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) throw new IllegalArgumentException("SEC response exceeded the configured byte limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean isFresh(Instant loadedAt, Instant now) {
        return loadedAt != null && now.isBefore(loadedAt.plus(cacheTtl));
    }

    private boolean isUsableStale(Instant loadedAt, Instant now) {
        return loadedAt != null && now.isBefore(loadedAt.plus(staleTtl));
    }

    private void evictOldestDetailEntries() {
        while (detailCache.size() > maxDetailEntries) {
            var oldest = detailCache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            detailCache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private void evictOldestTextEntries() {
        while (textCache.size() > maxTextEntries) {
            var oldest = textCache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            textCache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private void evictOldestRevenueMixEntries() {
        while (revenueMixCache.size() > maxTextEntries) {
            var oldest = revenueMixCache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            revenueMixCache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static String filingDirectory(String cik, String accessionNumber) {
        return "/Archives/edgar/data/" + Long.parseLong(cik) + "/"
                + accessionNumber.replace("-", "") + "/";
    }

    private static String normalizeCik(String cik) {
        if (cik == null) throw new IllegalArgumentException("cik is required");
        var digits = cik.replaceAll("\\D+", "");
        if (digits.isEmpty() || digits.length() > 10) throw new IllegalArgumentException("invalid CIK");
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static String normalizeAccession(String value) {
        if (value == null || !value.matches("\\d{10}-\\d{2}-\\d{6}")) {
            throw new IllegalArgumentException("invalid SEC accession number");
        }
        return value;
    }

    private static URI requireHttpsBaseUrl(URI value) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null) {
            throw new IllegalArgumentException("SEC filing baseUrl must be an absolute HTTPS URI");
        }
        var text = value.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static int effectivePort(URI value) {
        return value.getPort() < 0 ? 443 : value.getPort();
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private record CachedDetail(CompanyFilingDetailEvidence value, Instant loadedAt) {
    }

    private record CachedContent(CompanyFilingDocumentContent value, Instant loadedAt) {
    }

    private record CachedRevenueMix(CompanyRevenueMixEvidence value, Instant loadedAt) {
    }

    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder value;
        private final int limit;
        private boolean limitReached;

        private BoundedTextWriter(int limit) {
            this.limit = limit;
            this.value = new StringBuilder(Math.min(limit, 8_192));
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            if (length <= 0) return;
            var remaining = limit - value.length();
            if (remaining <= 0) {
                limitReached = true;
                return;
            }
            var accepted = Math.min(remaining, length);
            value.append(buffer, offset, accepted);
            if (accepted < length) limitReached = true;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String value() {
            return value.toString();
        }

        private boolean limitReached() {
            return limitReached;
        }
    }
}
