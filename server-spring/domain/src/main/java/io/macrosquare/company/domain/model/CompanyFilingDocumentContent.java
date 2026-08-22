package io.macrosquare.company.domain.model;

import java.util.Objects;

/**
 * Framework-free, bounded text evidence extracted from one filing document.
 */
public record CompanyFilingDocumentContent(
        String text,
        Format format,
        Integer totalPages,
        Integer processedPages,
        boolean truncated
) {
    public CompanyFilingDocumentContent {
        text = Objects.requireNonNull(text, "text");
        format = Objects.requireNonNull(format, "format");
        if (format == Format.PDF) {
            if (totalPages == null || totalPages < 1) {
                throw new IllegalArgumentException("PDF totalPages must be positive");
            }
            if (processedPages == null || processedPages < 1 || processedPages > totalPages) {
                throw new IllegalArgumentException("PDF processedPages must be within totalPages");
            }
        } else if (totalPages != null || processedPages != null) {
            throw new IllegalArgumentException("page counts are supported only for PDF documents");
        }
    }

    public int textCharacters() {
        return text.length();
    }

    public boolean hasText() {
        return !text.isBlank();
    }

    public enum Format {
        HTML("html"),
        TEXT("txt"),
        PDF("pdf");

        private final String value;

        Format(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
