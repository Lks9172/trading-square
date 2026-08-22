package io.macrosquare.policy.adapter.out.fed;

import io.macrosquare.policy.application.port.out.CollectPolicyDocumentsPort;
import io.macrosquare.policy.application.port.out.PolicyCollectionException;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Official Federal Reserve monetary-policy RSS + linked source-page collector. */
public final class FedMonetaryPolicyAdapter implements CollectPolicyDocumentsPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(FedMonetaryPolicyAdapter.class);
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern HISTORICAL_STATEMENT = Pattern.compile(
            "(?i)href=[\"']([^\"']*monetary([0-9]{8})a\\.htm)[\"']");

    private final RestClient restClient;
    private final URI feedUrl;
    private final URI calendarUrl;
    private final int historicalStatementLimit;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final Duration interRequestDelay;
    private final long maximumFeedBytes;
    private final long maximumDocumentBytes;
    private final AtomicLong nextRequestNanos = new AtomicLong();

    public FedMonetaryPolicyAdapter(
            RestClient restClient,
            URI feedUrl,
            ObjectStorage objectStorage,
            Clock clock,
            Duration interRequestDelay,
            long maximumFeedBytes,
            long maximumDocumentBytes
    ) {
        this(restClient, feedUrl, null, 0, objectStorage, clock, interRequestDelay,
                maximumFeedBytes, maximumDocumentBytes);
    }

    public FedMonetaryPolicyAdapter(
            RestClient restClient,
            URI feedUrl,
            URI calendarUrl,
            int historicalStatementLimit,
            ObjectStorage objectStorage,
            Clock clock,
            Duration interRequestDelay,
            long maximumFeedBytes,
            long maximumDocumentBytes
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.feedUrl = officialFedUrl(feedUrl);
        this.calendarUrl = calendarUrl == null ? null : officialFedUrl(calendarUrl);
        if (historicalStatementLimit < 0 || historicalStatementLimit > 120) {
            throw new IllegalArgumentException("historicalStatementLimit must be between 0 and 120");
        }
        this.historicalStatementLimit = historicalStatementLimit;
        this.objectStorage = objectStorage;
        this.clock = Objects.requireNonNull(clock);
        this.interRequestDelay = Objects.requireNonNull(interRequestDelay);
        if (interRequestDelay.isNegative()) throw new IllegalArgumentException("interRequestDelay must not be negative");
        if (maximumFeedBytes <= 0 || maximumDocumentBytes <= 0) {
            throw new IllegalArgumentException("Fed byte limits must be positive");
        }
        this.maximumFeedBytes = maximumFeedBytes;
        this.maximumDocumentBytes = maximumDocumentBytes;
    }

    @Override
    public List<PolicyDocument> collect(int maximumDocuments) {
        if (maximumDocuments < 1 || maximumDocuments > 120) {
            throw new IllegalArgumentException("maximumDocuments must be between 1 and 120");
        }
        try {
            var feed = fetch(feedUrl, maximumFeedBytes, MediaType.APPLICATION_XML);
            var references = new LinkedHashMap<String, Reference>();
            feed(feed, Math.min(maximumDocuments, 20)).forEach(value -> references.put(value.id(), value));
            if (calendarUrl != null && historicalStatementLimit > 0 && references.size() < maximumDocuments) {
                var calendar = fetch(calendarUrl, maximumFeedBytes, MediaType.TEXT_HTML);
                historicalStatements(calendar, calendarUrl, historicalStatementLimit)
                        .forEach(value -> references.putIfAbsent(value.id(), value));
            }
            var result = new ArrayList<PolicyDocument>();
            for (var reference : references.values().stream()
                    .sorted(java.util.Comparator.comparing(Reference::publishedAt).reversed())
                    .limit(maximumDocuments).toList()) {
                try {
                    result.add(document(reference));
                } catch (RuntimeException error) {
                    LOGGER.warn("Unable to collect Fed source document {}; using RSS metadata", reference.url(), error);
                    result.add(new PolicyDocument(
                            reference.id(), "Federal Reserve", reference.title(), type(reference.title()),
                            reference.publishedAt(), reference.url().toString(),
                            reference.title() + ". " + reference.description()));
                }
            }
            return List.copyOf(result);
        } catch (PolicyCollectionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new PolicyCollectionException("Unable to collect official Fed monetary-policy documents", error);
        }
    }

    private PolicyDocument document(Reference reference) {
        var html = fetch(reference.url(), maximumDocumentBytes, MediaType.TEXT_HTML);
        archive(reference, html);
        var text = articleText(new String(html, StandardCharsets.UTF_8));
        if (text.isBlank()) text = reference.title() + ". " + reference.description();
        return new PolicyDocument(
                reference.id(), "Federal Reserve", reference.title(), type(reference.title()),
                reference.publishedAt(), reference.url().toString(), text);
    }

    private byte[] fetch(URI url, long maximumBytes, MediaType accept) {
        officialFedUrl(url);
        pace();
        return restClient.get().uri(url).accept(accept).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
            try {
                return bounded(response.getBody(), maximumBytes);
            } catch (IOException error) {
                throw new PolicyCollectionException("Unable to read Fed source response", error);
            }
        });
    }

    private void archive(Reference reference, byte[] html) {
        if (objectStorage == null) return;
        objectStorage.put(
                "source-documents/fed/" + reference.id() + ".html",
                html,
                MediaType.TEXT_HTML_VALUE,
                Map.of(
                        "source", "federal-reserve",
                        "source-url", reference.url().toString(),
                        "published-at", reference.publishedAt().toString(),
                        "collected-at", clock.instant().toString()
                )
        );
    }

    private static List<Reference> feed(byte[] xml, int limit) {
        var factory = XMLInputFactory.newFactory();
        safeProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        safeProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            var result = new ArrayList<Reference>();
            MutableReference current = null;
            String field = null;
            while (reader.hasNext() && result.size() < limit) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    var local = reader.getLocalName();
                    if ("item".equals(local)) current = new MutableReference();
                    else if (current != null) field = local;
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && current != null && field != null) {
                    current.set(field, reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    var local = reader.getLocalName();
                    if ("item".equals(local) && current != null) {
                        var value = current.build();
                        if (value != null) result.add(value);
                        current = null;
                    }
                    field = null;
                }
            }
            reader.close();
            return List.copyOf(result);
        } catch (XMLStreamException error) {
            throw new PolicyCollectionException("Fed RSS is invalid XML", error);
        }
    }

    private static List<Reference> historicalStatements(byte[] html, URI calendarUrl, int limit) {
        var source = new String(html, StandardCharsets.UTF_8);
        var matcher = HISTORICAL_STATEMENT.matcher(source);
        var result = new LinkedHashMap<String, Reference>();
        while (matcher.find() && result.size() < limit) {
            try {
                var url = officialFedUrl(calendarUrl.resolve(matcher.group(1)));
                var compactDate = matcher.group(2);
                var date = LocalDate.parse(compactDate, DateTimeFormatter.BASIC_ISO_DATE);
                var id = "monetary" + compactDate + "a";
                result.putIfAbsent(id, new Reference(
                        id, "Federal Reserve issues FOMC statement", url,
                        "Official historical FOMC statement", date.atTime(18, 0).toInstant(ZoneOffset.UTC)));
            } catch (RuntimeException ignored) {
                // One malformed calendar link must not discard the historical archive.
            }
        }
        return List.copyOf(result.values());
    }

    private static String articleText(String html) {
        var start = html.toLowerCase(Locale.ROOT).indexOf("<div id=\"article\"");
        if (start < 0) start = html.toLowerCase(Locale.ROOT).indexOf("<div id='article'");
        var end = html.toLowerCase(Locale.ROOT).indexOf("<div id=\"lastupdate\"", Math.max(0, start));
        if (end < 0) end = html.length();
        var article = html.substring(Math.max(0, start), Math.max(Math.max(0, start), end));
        article = SCRIPT_STYLE.matcher(article).replaceAll(" ");
        article = article.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>", "\n");
        article = TAG.matcher(article).replaceAll(" ");
        article = decodeEntities(article);
        return article.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n+ *", "\n").trim();
    }

    private static String decodeEntities(String value) {
        var decoded = value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        var matcher = NUMERIC_ENTITY.matcher(decoded);
        var result = new StringBuffer();
        while (matcher.find()) {
            try {
                var raw = matcher.group(1);
                var codePoint = raw.startsWith("x") || raw.startsWith("X")
                        ? Integer.parseInt(raw.substring(1), 16) : Integer.parseInt(raw);
                matcher.appendReplacement(result, Matcher.quoteReplacement(Character.toString(codePoint)));
            } catch (RuntimeException ignored) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static PolicyDocumentType type(String title) {
        var value = title.toLowerCase(Locale.ROOT);
        if (value.contains("fomc statement")) return PolicyDocumentType.FOMC_STATEMENT;
        if (value.contains("economic projections")) return PolicyDocumentType.ECONOMIC_PROJECTIONS;
        if (value.contains("discount rate")) return PolicyDocumentType.DISCOUNT_RATE_MINUTES;
        if (value.contains("minutes of the federal open market committee")) return PolicyDocumentType.FOMC_MINUTES;
        return PolicyDocumentType.OTHER;
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
                throw new PolicyCollectionException("Interrupted while pacing Fed requests", error);
            }
        }
    }

    private static URI officialFedUrl(URI value) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || !"www.federalreserve.gov".equalsIgnoreCase(value.getHost())) {
            throw new IllegalArgumentException("Fed source URL must use the official federalreserve.gov host");
        }
        return value;
    }

    private static byte[] bounded(InputStream input, long maximumBytes) throws IOException {
        if (input == null) throw new IOException("Fed response body was empty");
        if (maximumBytes >= Integer.MAX_VALUE) throw new IllegalArgumentException("maximumBytes is too large");
        var bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("Fed response exceeded configured byte limit");
        return bytes;
    }

    private static void safeProperty(XMLInputFactory factory, String key, boolean value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private record Reference(
            String id,
            String title,
            URI url,
            String description,
            Instant publishedAt
    ) {
    }

    private static final class MutableReference {
        private String title = "";
        private String link = "";
        private String description = "";
        private String published = "";

        private void set(String field, String raw) {
            var value = raw == null ? "" : raw.trim();
            switch (field) {
                case "title" -> title += value;
                case "link" -> link += value;
                case "description" -> description += value;
                case "pubDate" -> published += value;
                default -> {
                }
            }
        }

        private Reference build() {
            try {
                var url = officialFedUrl(URI.create(link));
                var path = url.getPath();
                var id = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
                var publishedAt = ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return title.isBlank() || id.isBlank() ? null
                        : new Reference(id, title, url, description, publishedAt);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
