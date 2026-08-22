package io.macrosquare.disclosure.adapter.out.opendart;

import io.macrosquare.disclosure.application.port.out.CollectDartCompanyDirectoryPort;
import io.macrosquare.disclosure.application.port.out.CollectDartDisclosuresPort;
import io.macrosquare.disclosure.application.port.out.CollectDartFinancialsPort;
import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;
import io.macrosquare.disclosure.domain.service.DartEventClassificationPolicy;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipInputStream;

/** Official OpenDART transport adapter. API keys never leave this infrastructure boundary. */
public final class OpenDartAdapter implements CollectDartCompanyDirectoryPort,
        CollectDartDisclosuresPort, CollectDartFinancialsPort {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DartEventClassificationPolicy classifier;
    private final ObjectStorage objectStorage;
    private final String apiKey;
    private final Clock clock;
    private final long maximumCompressedBytes;
    private final long maximumUncompressedBytes;
    private final long delayNanos;
    private final AtomicLong nextRequestNanos = new AtomicLong();

    public OpenDartAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            DartEventClassificationPolicy classifier,
            ObjectStorage objectStorage,
            String apiKey,
            Clock clock,
            Duration interRequestDelay,
            long maximumCompressedBytes,
            long maximumUncompressedBytes
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.classifier = Objects.requireNonNull(classifier);
        this.objectStorage = objectStorage;
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OpenDART apiKey is required");
        this.apiKey = apiKey.trim();
        this.clock = Objects.requireNonNull(clock);
        this.delayNanos = Objects.requireNonNull(interRequestDelay).toNanos();
        if (delayNanos < 0 || maximumCompressedBytes <= 0 || maximumUncompressedBytes <= 0
                || maximumCompressedBytes >= Integer.MAX_VALUE || maximumUncompressedBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("OpenDART boundaries are invalid");
        }
        this.maximumCompressedBytes = maximumCompressedBytes;
        this.maximumUncompressedBytes = maximumUncompressedBytes;
    }

    @Override
    public List<DartCompany> collect() {
        var zip = get("/api/corpCode.xml", builder -> builder.queryParam("crtfc_key", apiKey),
                maximumCompressedBytes, MediaType.APPLICATION_OCTET_STREAM);
        archive("directory/" + LocalDate.now(clock) + ".zip", zip, "opendart-corporate-directory");
        return parseCompanies(unzipCorporateDirectory(zip));
    }

    @Override
    public List<DartDisclosure> collect(DartCompany company, LocalDate from, LocalDate to, int limit) {
        var bytes = get("/api/list.json", builder -> builder
                        .queryParam("crtfc_key", apiKey).queryParam("corp_code", company.corpCode())
                        .queryParam("bgn_de", BASIC_DATE.format(from)).queryParam("end_de", BASIC_DATE.format(to))
                        .queryParam("page_no", 1).queryParam("page_count", Math.min(100, limit)),
                maximumUncompressedBytes, MediaType.APPLICATION_JSON);
        archive("disclosures/" + company.corpCode() + "/" + to + ".json", bytes, "opendart-disclosures");
        var root = json(bytes);
        if (noData(root)) return List.of();
        requireSuccess(root);
        var result = new ArrayList<DartDisclosure>();
        var list = root.get("list");
        if (list == null || !list.isArray()) return List.of();
        for (var node : list) {
            var receipt = text(node, "rcept_no");
            var reportName = text(node, "report_nm");
            result.add(new DartDisclosure(
                    receipt, text(node, "corp_code"), text(node, "corp_name"), reportName,
                    nullableText(node, "flr_nm"), LocalDate.parse(text(node, "rcept_dt"), BASIC_DATE),
                    nullableText(node, "rm"), classifier.classify(reportName),
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt));
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    @Override
    public List<DartFinancialMetric> collect(DartCompany company, int businessYear, String reportCode) {
        var bytes = get("/api/fnlttSinglAcntAll.json", builder -> builder
                        .queryParam("crtfc_key", apiKey).queryParam("corp_code", company.corpCode())
                        .queryParam("bsns_year", businessYear).queryParam("reprt_code", reportCode)
                        .queryParam("fs_div", "CFS"), maximumUncompressedBytes, MediaType.APPLICATION_JSON);
        archive("financials/" + company.corpCode() + "/" + businessYear + "-" + reportCode + ".json",
                bytes, "opendart-financials");
        var root = json(bytes);
        if (noData(root)) return List.of();
        requireSuccess(root);
        var result = new ArrayList<DartFinancialMetric>();
        var list = root.get("list");
        if (list == null || !list.isArray()) return List.of();
        for (var node : list) {
            result.add(new DartFinancialMetric(
                    company.corpCode(), businessYear, reportCode, text(node, "sj_div"),
                    text(node, "sj_nm"), fallback(node, "account_id", "unknown:" + result.size()),
                    text(node, "account_nm"), amount(node, "thstrm_amount"),
                    amount(node, "frmtrm_amount"), nullableText(node, "currency")));
        }
        return List.copyOf(result);
    }

    private byte[] get(
            String path,
            java.util.function.UnaryOperator<org.springframework.web.util.UriBuilder> query,
            long maximumBytes,
            MediaType accept
    ) {
        pace();
        return restClient.get().uri(builder -> query.apply(builder.path(path)).build()).accept(accept)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                    try {
                        return bounded(response.getBody(), maximumBytes);
                    } catch (IOException error) {
                        throw new IllegalArgumentException("OpenDART response exceeded boundary", error);
                    }
                });
    }

    private JsonNode json(byte[] bytes) {
        try {
            return objectMapper.readTree(bytes);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("OpenDART returned invalid JSON", error);
        }
    }

    private static boolean noData(JsonNode root) {
        return "013".equals(nullableText(root, "status"));
    }

    private static void requireSuccess(JsonNode root) {
        var status = nullableText(root, "status");
        if (!"000".equals(status)) {
            throw new IllegalArgumentException("OpenDART error status " + status + ": " + nullableText(root, "message"));
        }
    }

    private List<DartCompany> parseCompanies(byte[] xml) {
        try {
            var factory = XMLInputFactory.newFactory();
            safeProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
            safeProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
            var result = new ArrayList<DartCompany>();
            MutableCompany current = null;
            String field = null;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("list".equals(reader.getLocalName())) current = new MutableCompany();
                    else if (current != null) field = reader.getLocalName();
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && current != null && field != null) {
                    current.set(field, reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("list".equals(reader.getLocalName()) && current != null) {
                        var value = current.build();
                        if (value != null) result.add(value);
                        current = null;
                    }
                    field = null;
                }
            }
            reader.close();
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to parse OpenDART corporate directory", error);
        }
    }

    private byte[] unzipCorporateDirectory(byte[] zip) {
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip));
             var output = new ByteArrayOutputStream()) {
            var entry = input.getNextEntry();
            if (entry == null || entry.isDirectory() || !"CORPCODE.xml".equalsIgnoreCase(entry.getName())) {
                throw new IllegalArgumentException("OpenDART directory ZIP did not contain CORPCODE.xml");
            }
            var buffer = new byte[8192];
            var total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximumUncompressedBytes) throw new IllegalArgumentException("OpenDART ZIP exceeds limit");
                output.write(buffer, 0, read);
            }
            input.closeEntry();
            if (input.getNextEntry() != null) throw new IllegalArgumentException("OpenDART ZIP contains unexpected entries");
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to decompress OpenDART corporate directory", error);
        }
    }

    private void archive(String key, byte[] bytes, String source) {
        if (objectStorage == null) return;
        objectStorage.put("source-documents/dart/" + key, bytes,
                key.endsWith(".zip") ? MediaType.APPLICATION_OCTET_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE,
                Map.of("source", source, "collected-at", clock.instant().toString()));
    }

    private static String text(JsonNode node, String field) {
        var value = nullableText(node, field);
        if (value.isBlank()) throw new IllegalArgumentException("OpenDART field " + field + " is required");
        return value;
    }

    private static String fallback(JsonNode node, String field, String fallback) {
        var value = nullableText(node, field);
        return value.isBlank() ? fallback : value;
    }

    private static String nullableText(JsonNode node, String field) {
        if (node == null) return "";
        var value = node.get(field);
        return value == null || value.isNull() ? "" : value.stringValue().trim();
    }

    private static BigDecimal amount(JsonNode node, String field) {
        var value = nullableText(node, field).replace(",", "").trim();
        if (value.isEmpty() || "-".equals(value)) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static byte[] bounded(InputStream input, long maximumBytes) throws IOException {
        if (input == null) throw new IOException("empty OpenDART response");
        var bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("OpenDART response is too large");
        return bytes;
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
                Thread.sleep(Duration.ofNanos(wait));
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pacing OpenDART requests", error);
            }
        }
    }

    private static void safeProperty(XMLInputFactory factory, String key, boolean value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static final class MutableCompany {
        private String corpCode = "";
        private String corpName = "";
        private String corpEnglishName = "";
        private String stockCode = "";
        private String modifiedOn = "";

        private void set(String field, String raw) {
            var value = raw == null ? "" : raw.trim();
            switch (field) {
                case "corp_code" -> corpCode += value;
                case "corp_name" -> corpName += value;
                case "corp_eng_name" -> corpEnglishName += value;
                case "stock_code" -> stockCode += value;
                case "modify_date" -> modifiedOn += value;
                default -> {
                }
            }
        }

        private DartCompany build() {
            try {
                return new DartCompany(
                        corpCode, stockCode, corpName, corpEnglishName,
                        LocalDate.parse(modifiedOn, BASIC_DATE));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
