package io.macrosquare.policy.adapter.out.official;

import io.macrosquare.policy.application.port.out.CollectPolicyDocumentsPort;
import io.macrosquare.policy.application.port.out.PolicyCollectionException;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded official HTML collector for Treasury releases and USTR tariff actions. */
public final class OfficialAgencyPolicyAdapter implements CollectPolicyDocumentsPort {

    private static final Pattern TIME = Pattern.compile("(?is)<time[^>]*datetime=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern LINK = Pattern.compile("(?is)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|nav|footer)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

    private final RestClient restClient;
    private final URI listingUrl;
    private final String officialHost;
    private final String source;
    private final String archivePrefix;
    private final PolicyDocumentType type;
    private final String requiredPathFragment;
    private final Set<String> relevanceKeywords;
    private final int sourceLimit;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final long maximumListingBytes;
    private final long maximumDocumentBytes;
    private final long delayNanos;
    private final AtomicLong nextRequestNanos = new AtomicLong();

    public OfficialAgencyPolicyAdapter(
            RestClient restClient,
            URI listingUrl,
            String officialHost,
            String source,
            String archivePrefix,
            PolicyDocumentType type,
            String requiredPathFragment,
            Set<String> relevanceKeywords,
            int sourceLimit,
            ObjectStorage objectStorage,
            Clock clock,
            java.time.Duration interRequestDelay,
            long maximumListingBytes,
            long maximumDocumentBytes
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.officialHost = required(officialHost, "officialHost").toLowerCase(Locale.ROOT);
        this.listingUrl = official(listingUrl);
        this.source = required(source, "source");
        this.archivePrefix = required(archivePrefix, "archivePrefix");
        this.type = Objects.requireNonNull(type);
        this.requiredPathFragment = required(requiredPathFragment, "requiredPathFragment");
        this.relevanceKeywords = Set.copyOf(relevanceKeywords == null ? Set.of() : relevanceKeywords);
        if (sourceLimit < 1 || sourceLimit > 30) throw new IllegalArgumentException("sourceLimit is out of range");
        this.sourceLimit = sourceLimit;
        this.objectStorage = objectStorage;
        this.clock = Objects.requireNonNull(clock);
        this.delayNanos = Objects.requireNonNull(interRequestDelay).toNanos();
        if (delayNanos < 0 || maximumListingBytes <= 0 || maximumDocumentBytes <= 0) {
            throw new IllegalArgumentException("official agency collector limits are invalid");
        }
        this.maximumListingBytes = maximumListingBytes;
        this.maximumDocumentBytes = maximumDocumentBytes;
    }

    @Override
    public List<PolicyDocument> collect(int maximumDocuments) {
        if (maximumDocuments < 1 || maximumDocuments > 120) {
            throw new IllegalArgumentException("maximumDocuments is out of range");
        }
        try {
            var listing = new String(fetch(listingUrl, maximumListingBytes), StandardCharsets.UTF_8);
            var references = references(listing).stream().limit(Math.min(sourceLimit, maximumDocuments)).toList();
            var result = new ArrayList<PolicyDocument>();
            for (var reference : references) {
                try {
                    var html = fetch(reference.url(), maximumDocumentBytes);
                    archive(reference, html);
                    var text = articleText(new String(html, StandardCharsets.UTF_8));
                    result.add(new PolicyDocument(
                            archivePrefix + "-" + reference.id(), source, reference.title(), type,
                            reference.publishedAt(), reference.url().toString(), text));
                } catch (RuntimeException ignored) {
                    result.add(new PolicyDocument(
                            archivePrefix + "-" + reference.id(), source, reference.title(), type,
                            reference.publishedAt(), reference.url().toString(), reference.title()));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new PolicyCollectionException("Unable to collect official " + source + " documents", error);
        }
    }

    private List<Reference> references(String html) {
        var times = new ArrayList<TimeMarker>();
        var timeMatcher = TIME.matcher(html);
        while (timeMatcher.find()) {
            try {
                times.add(new TimeMarker(timeMatcher.start(), Instant.parse(timeMatcher.group(1))));
            } catch (RuntimeException ignored) {
            }
        }
        var result = new LinkedHashMap<String, Reference>();
        for (var index = 0; index < times.size(); index++) {
            var marker = times.get(index);
            var end = index + 1 < times.size() ? times.get(index + 1).start() : Math.min(html.length(), marker.start() + 2500);
            var window = html.substring(marker.start(), Math.min(end, marker.start() + 2500));
            var linkMatcher = LINK.matcher(window);
            while (linkMatcher.find()) {
                var rawUrl = linkMatcher.group(1);
                if (!rawUrl.contains(requiredPathFragment)) continue;
                try {
                    var url = official(listingUrl.resolve(rawUrl));
                    var title = clean(linkMatcher.group(2));
                    if (title.isBlank() || !relevant(title)) continue;
                    var id = stableId(url);
                    result.putIfAbsent(id, new Reference(id, title, url, marker.publishedAt()));
                    break;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return result.values().stream().sorted(Comparator.comparing(Reference::publishedAt).reversed()).toList();
    }

    private boolean relevant(String title) {
        if (relevanceKeywords.isEmpty()) return true;
        var normalized = title.toLowerCase(Locale.ROOT);
        return relevanceKeywords.stream().anyMatch(normalized::contains);
    }

    private byte[] fetch(URI url, long maximumBytes) {
        official(url);
        pace();
        return restClient.get().uri(url).accept(MediaType.TEXT_HTML).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
            try {
                return bounded(response.getBody(), maximumBytes);
            } catch (IOException error) {
                throw new PolicyCollectionException("Official policy response exceeded its boundary", error);
            }
        });
    }

    private void archive(Reference reference, byte[] html) {
        if (objectStorage == null) return;
        objectStorage.put(
                "source-documents/" + archivePrefix + "/" + reference.id() + ".html",
                html, MediaType.TEXT_HTML_VALUE,
                Map.of("source", archivePrefix, "source-url", reference.url().toString(),
                        "published-at", reference.publishedAt().toString(),
                        "collected-at", clock.instant().toString()));
    }

    private static String articleText(String html) {
        var lower = html.toLowerCase(Locale.ROOT);
        var start = lower.indexOf("<main");
        var end = start < 0 ? -1 : lower.indexOf("</main>", start);
        var body = start >= 0 ? html.substring(start, end < 0 ? html.length() : end) : html;
        body = SCRIPT_STYLE.matcher(body).replaceAll(" ");
        body = body.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>", "\n");
        return clean(body).replaceAll(" *\n+ *", "\n").trim();
    }

    private static String clean(String value) {
        return TAG.matcher(value).replaceAll(" ")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private void pace() {
        if (delayNanos <= 0) return;
        while (true) {
            var observed = nextRequestNanos.get();
            var now = System.nanoTime();
            var reserved = Math.max(observed, now);
            if (!nextRequestNanos.compareAndSet(observed, reserved + delayNanos)) continue;
            var wait = reserved - now;
            if (wait <= 0) return;
            try {
                Thread.sleep(java.time.Duration.ofNanos(wait));
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PolicyCollectionException("Interrupted while pacing official policy requests", error);
            }
        }
    }

    private URI official(URI value) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || !officialHost.equalsIgnoreCase(value.getHost())) {
            throw new IllegalArgumentException("Policy source must use official host " + officialHost);
        }
        return value;
    }

    private static byte[] bounded(InputStream input, long maximumBytes) throws IOException {
        if (input == null) throw new IOException("empty policy response");
        if (maximumBytes >= Integer.MAX_VALUE) throw new IllegalArgumentException("maximumBytes is too large");
        var bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("policy response exceeded configured limit");
        return bytes;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    /** Keeps PostgreSQL identifiers bounded while retaining a collision-resistant URL identity. */
    private static String stableId(URI url) {
        var path = url.getPath();
        var lastSegment = path == null || path.isBlank()
                ? "document"
                : path.substring(path.lastIndexOf('/') + 1);
        var slug = lastSegment.replaceAll("[^a-zA-Z0-9_-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) slug = "document";
        if (slug.length() > 72) slug = slug.substring(0, 72);
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.toString().getBytes(StandardCharsets.UTF_8));
            return slug + "-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM is missing SHA-256", impossible);
        }
    }

    private record TimeMarker(int start, Instant publishedAt) {
    }

    private record Reference(String id, String title, URI url, Instant publishedAt) {
    }
}
