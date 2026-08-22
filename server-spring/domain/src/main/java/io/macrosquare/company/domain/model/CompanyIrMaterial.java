package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A transport-neutral investor-relations material linked to an SEC filing.
 */
public record CompanyIrMaterial(
        String title,
        String form,
        LocalDate filingDate,
        String url,
        Type type,
        Source source,
        ContentType contentType,
        String summary
) {
    public CompanyIrMaterial {
        title = requireText(title, "title");
        form = requireText(form, "form");
        filingDate = Objects.requireNonNull(filingDate, "filingDate");
        url = requireText(url, "url");
        type = Objects.requireNonNull(type, "type");
        source = Objects.requireNonNull(source, "source");
        contentType = Objects.requireNonNull(contentType, "contentType");
    }

    public CompanyIrMaterial withSummary(String value) {
        return new CompanyIrMaterial(title, form, filingDate, url, type, source, contentType, value);
    }

    public String identityKey() {
        return url + "\n" + title;
    }

    public enum Type {
        PRESENTATION("presentation"),
        EARNINGS_RELEASE("earnings-release"),
        ANNUAL_REPORT("annual-report"),
        QUARTERLY_REPORT("quarterly-report"),
        OTHER("other");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Type fromValue(String value) {
            for (var candidate : values()) {
                if (candidate.value.equals(value)) return candidate;
            }
            throw new IllegalArgumentException("unsupported IR material type: " + value);
        }
    }

    public enum Source {
        PRIMARY("primary"),
        EXHIBIT("exhibit"),
        INDEX("index");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Source fromValue(String value) {
            for (var candidate : values()) {
                if (candidate.value.equals(value)) return candidate;
            }
            throw new IllegalArgumentException("unsupported IR material source: " + value);
        }
    }

    public enum ContentType {
        PDF("pdf"),
        HTML("html"),
        TXT("txt"),
        OTHER("other");

        private final String value;

        ContentType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static ContentType fromValue(String value) {
            for (var candidate : values()) {
                if (candidate.value.equals(value)) return candidate;
            }
            throw new IllegalArgumentException("unsupported IR material content type: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
