package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFilingDocumentEvidence;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyIrMaterial;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Pure classification and compatibility-summary rules for SEC-backed IR materials. */
public final class CompanyIrMaterialPolicy {

    private static final Pattern PRESENTATION = Pattern.compile(
            "presentation|slides|deck|supplement|investor",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EARNINGS = Pattern.compile(
            "earnings|results|quarterly results|exhibit[ _-]?99|ex-?99",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TRACKED_DOCUMENT = Pattern.compile(
            "ex-?99(?:\\.|[-_])?|exhibit[ _-]?99|presentation|slides|deck|supplement|investor",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MACHINE_READABLE_PRESENTATION_LINKBASE = Pattern.compile(
            "xbrl|taxonomy|linkbase|ex-?101",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RETAINED_OTHER = Pattern.compile(
            "investor|presentation|earnings|annual|quarter|exhibit[ _-]?99|ex-?99",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SUMMARY_SENTENCE = Pattern.compile(
            "[^.!?\\n]{0,220}(investor presentation|revenue|guidance|free cash flow|capital expenditure)"
                    + "[^.!?\\n]{0,260}[.!?]?",
            Pattern.CASE_INSENSITIVE
    );

    public Optional<CompanyIrMaterial> primaryMaterial(
            CompanyFilingEvidence filing,
            boolean earningsRelated
    ) {
        if (filing.sourceUrl() == null || filing.sourceUrl().isBlank()) return Optional.empty();
        var title = firstText(
                filing.primaryDocumentDescription(),
                filing.primaryDocument(),
                filing.form()
        );
        var searchable = title + " " + nullToEmpty(filing.primaryDocument());
        var type = classifyPrimaryType(filing.form(), searchable, earningsRelated);
        return Optional.of(new CompanyIrMaterial(
                title,
                filing.form(),
                filing.filingDate(),
                filing.sourceUrl(),
                type,
                CompanyIrMaterial.Source.PRIMARY,
                contentType(filing.sourceUrl()),
                null
        ));
    }

    public Optional<CompanyIrMaterial> indexedMaterial(
            CompanyFilingEvidence filing,
            CompanyFilingDocumentEvidence document
    ) {
        if (!isTrackedDocument(document)) return Optional.empty();
        var searchable = nullToEmpty(document.description()) + " "
                + document.documentName() + " " + nullToEmpty(document.documentType());
        var type = PRESENTATION.matcher(searchable).find()
                ? CompanyIrMaterial.Type.PRESENTATION
                : CompanyIrMaterial.Type.EARNINGS_RELEASE;
        var title = firstText(document.description(), document.documentName());
        return Optional.of(new CompanyIrMaterial(
                title,
                filing.form(),
                filing.filingDate(),
                document.sourceUrl(),
                type,
                CompanyIrMaterial.Source.INDEX,
                contentType(document.sourceUrl()),
                null
        ));
    }

    public boolean isTrackedDocument(CompanyFilingDocumentEvidence document) {
        var searchable = nullToEmpty(document.description()) + " "
                + document.documentName() + " " + nullToEmpty(document.documentType());
        return contentType(document.sourceUrl()) != CompanyIrMaterial.ContentType.OTHER
                && !MACHINE_READABLE_PRESENTATION_LINKBASE.matcher(searchable).find()
                && TRACKED_DOCUMENT.matcher(searchable).find();
    }

    public boolean shouldRetain(CompanyIrMaterial material) {
        return material.type() != CompanyIrMaterial.Type.OTHER
                || RETAINED_OTHER.matcher(material.title()).find();
    }

    public Optional<String> summarize(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        var matches = new ArrayList<String>(2);
        var matcher = SUMMARY_SENTENCE.matcher(text);
        while (matcher.find() && matches.size() < 2) {
            var normalized = matcher.group().replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) matches.add(normalized);
        }
        if (matches.isEmpty()) return Optional.empty();
        var summary = String.join(" ", matches);
        return Optional.of(summary.length() <= 320 ? summary : summary.substring(0, 320));
    }

    public CompanyIrMaterial.ContentType contentType(String url) {
        var normalized = url.toLowerCase(Locale.ROOT);
        if (pathEndsWith(normalized, ".pdf")) return CompanyIrMaterial.ContentType.PDF;
        if (pathEndsWith(normalized, ".txt")) return CompanyIrMaterial.ContentType.TXT;
        if (pathEndsWith(normalized, ".htm") || pathEndsWith(normalized, ".html")
                || pathEndsWith(normalized, ".xml")) {
            return CompanyIrMaterial.ContentType.HTML;
        }
        return CompanyIrMaterial.ContentType.OTHER;
    }

    private static CompanyIrMaterial.Type classifyPrimaryType(
            String form,
            String searchable,
            boolean earningsRelated
    ) {
        if (PRESENTATION.matcher(searchable).find()) return CompanyIrMaterial.Type.PRESENTATION;
        if ("10-K".equals(form)) return CompanyIrMaterial.Type.ANNUAL_REPORT;
        if ("10-Q".equals(form)) return CompanyIrMaterial.Type.QUARTERLY_REPORT;
        if (earningsRelated || EARNINGS.matcher(searchable).find()) {
            return CompanyIrMaterial.Type.EARNINGS_RELEASE;
        }
        return CompanyIrMaterial.Type.OTHER;
    }

    private static boolean pathEndsWith(String value, String suffix) {
        var query = value.indexOf('?');
        var path = query < 0 ? value : value.substring(0, query);
        return path.endsWith(suffix);
    }

    private static String firstText(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        throw new IllegalArgumentException("at least one non-blank value is required");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
