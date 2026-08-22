package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.domain.model.CompanyFilingDetailEvidence;
import io.macrosquare.company.domain.model.CompanyFilingDocumentEvidence;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Tolerant parser for the stable EDGAR filing-document table. */
final class SecFilingIndexParser {

    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td\\b[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private SecFilingIndexParser() {
    }

    static CompanyFilingDetailEvidence parse(
            String html,
            String cik,
            String accessionNumber,
            URI indexUri,
            URI allowedOrigin
    ) {
        if (html == null) throw new IllegalArgumentException("SEC filing index HTML is required");
        var normalizedCik = normalizeCik(cik);
        if (accessionNumber == null || !accessionNumber.matches("\\d{10}-\\d{2}-\\d{6}")) {
            throw new IllegalArgumentException("invalid SEC accession number");
        }
        requireAllowedIndex(indexUri, allowedOrigin, normalizedCik, accessionNumber);
        var expectedDirectory = filingDirectory(normalizedCik, accessionNumber);
        var documents = new LinkedHashMap<String, CompanyFilingDocumentEvidence>();
        var rows = ROW.matcher(html);
        while (rows.find()) {
            var cells = cells(rows.group(1));
            if (cells.size() < 5) continue;
            var link = LINK.matcher(cells.get(2));
            if (!link.find()) continue;
            var href = decodeEntities(link.group(2)).trim();
            var resolved = resolveDocument(indexUri, allowedOrigin, expectedDirectory, href);
            if (resolved == null) continue;
            var documentName = plainText(link.group(3));
            if (documentName.isBlank()) documentName = fileName(resolved.getPath());
            if (documentName.isBlank()) continue;
            var evidence = new CompanyFilingDocumentEvidence(
                    nonNegativeInt(plainText(cells.get(0))),
                    blankToNull(plainText(cells.get(1))),
                    documentName,
                    blankToNull(plainText(cells.get(3))),
                    nullableLong(plainText(cells.get(4))),
                    resolved.toASCIIString()
            );
            documents.putIfAbsent(evidence.sourceUrl(), evidence);
        }
        return new CompanyFilingDetailEvidence(
                normalizedCik,
                accessionNumber,
                indexUri.toASCIIString(),
                List.copyOf(documents.values())
        );
    }

    static String plainText(String html) {
        if (html == null || html.isEmpty()) return "";
        return decodeEntities(TAG.matcher(html).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }

    static String decodeEntities(String value) {
        var decoded = value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        var matcher = NUMERIC_ENTITY.matcher(decoded);
        var output = new StringBuilder(decoded.length());
        while (matcher.find()) {
            try {
                var token = matcher.group(1);
                var codePoint = token.startsWith("x") || token.startsWith("X")
                        ? Integer.parseInt(token.substring(1), 16)
                        : Integer.parseInt(token);
                matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(
                        Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : ""
                ));
            } catch (NumberFormatException error) {
                matcher.appendReplacement(output, "");
            }
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static List<String> cells(String row) {
        var values = new ArrayList<String>(5);
        var matcher = CELL.matcher(row);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private static URI resolveDocument(
            URI indexUri,
            URI allowedOrigin,
            String expectedDirectory,
            String href
    ) {
        if (href.isBlank() || href.indexOf('\\') >= 0) return null;
        final URI resolved;
        try {
            resolved = indexUri.resolve(href).normalize();
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!sameOrigin(resolved, allowedOrigin)) return null;
        var rawPath = resolved.getRawPath();
        if (rawPath == null || rawPath.toLowerCase(Locale.ROOT).contains("%2e")) return null;
        if (!resolved.getPath().startsWith(expectedDirectory)) return null;
        if (resolved.getUserInfo() != null || resolved.getFragment() != null) return null;
        return resolved;
    }

    private static void requireAllowedIndex(
            URI indexUri,
            URI allowedOrigin,
            String cik,
            String accessionNumber
    ) {
        if (!sameOrigin(indexUri, allowedOrigin)
                || !indexUri.getPath().startsWith(filingDirectory(cik, accessionNumber))) {
            throw new IllegalArgumentException("SEC filing index URI escaped the configured archive origin");
        }
    }

    private static boolean sameOrigin(URI value, URI expected) {
        return value != null
                && "https".equalsIgnoreCase(value.getScheme())
                && expected.getHost() != null
                && expected.getHost().equalsIgnoreCase(value.getHost())
                && effectivePort(value) == effectivePort(expected);
    }

    private static int effectivePort(URI value) {
        return value.getPort() < 0 ? 443 : value.getPort();
    }

    private static String filingDirectory(String cik, String accessionNumber) {
        return "/Archives/edgar/data/" + Long.parseLong(cik) + "/"
                + accessionNumber.replace("-", "") + "/";
    }

    private static String fileName(String path) {
        if (path == null) return "";
        var slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static int nonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.replaceAll("[^0-9]", "")));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static Long nullableLong(String value) {
        var digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeCik(String cik) {
        if (cik == null) throw new IllegalArgumentException("cik is required");
        var digits = cik.replaceAll("\\D+", "");
        if (digits.isEmpty() || digits.length() > 10) throw new IllegalArgumentException("invalid CIK");
        return "0".repeat(10 - digits.length()) + digits;
    }
}
