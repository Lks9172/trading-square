package io.macrosquare.research.adapter.out.official;

import io.macrosquare.research.domain.rotation.SectorFundHistoryPoint;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Minimal bounded OOXML reader for State Street NAV history workbooks. */
final class StateStreetFundHistoryWorkbookParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.US);
    private static final int MAX_UNCOMPRESSED_BYTES = 8 * 1024 * 1024;

    List<SectorFundHistoryPoint> parse(byte[] workbook, String expectedTicker) {
        if (workbook == null || workbook.length == 0) throw new IllegalArgumentException("workbook is empty");
        var entries = unzipRequiredEntries(workbook);
        var sharedStrings = parseSharedStrings(required(entries, "xl/sharedStrings.xml"));
        var rows = parseRows(required(entries, "xl/worksheets/sheet1.xml"), sharedStrings);
        var ticker = value(rows.get(2), "B");
        if (ticker == null || !ticker.equalsIgnoreCase(expectedTicker)) {
            throw new IllegalArgumentException("fund ticker did not match the requested ETF");
        }
        if (!"Date".equals(value(rows.get(4), "A"))
                || !"NAV".equals(value(rows.get(4), "B"))
                || !"Shares Outstanding".equals(value(rows.get(4), "C"))
                || !"Total Net Assets".equals(value(rows.get(4), "D"))) {
            throw new IllegalArgumentException("fund history workbook columns changed");
        }
        var result = new ArrayList<SectorFundHistoryPoint>();
        rows.entrySet().stream().filter(entry -> entry.getKey() >= 5).sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var row = entry.getValue();
                    var rawDate = value(row, "A");
                    if (rawDate == null || rawDate.isBlank()) return;
                    // The issuer workbook contains historical rows whose NAV,
                    // shares or assets are explicitly unavailable as "-".
                    // They are not observations and must not invalidate later,
                    // complete rows; unexpected non-numeric values still fail.
                    if (unavailable(value(row, "B"))
                            || unavailable(value(row, "C"))
                            || unavailable(value(row, "D"))) return;
                    // XLC's official history contains one pre-launch row with a
                    // stated NAV but no issued shares or assets. It represents
                    // no investable fund observation, so skip only the exact
                    // zero-capitalization pair. A partial/non-finite/negative
                    // zero remains invalid and fails closed below.
                    if (zeroCapitalization(value(row, "C"), value(row, "D"))) return;
                    try {
                        result.add(new SectorFundHistoryPoint(
                                LocalDate.parse(rawDate, DATE),
                                positive(value(row, "B"), "NAV"),
                                positive(value(row, "C"), "shares outstanding"),
                                positive(value(row, "D"), "total net assets")
                        ));
                    } catch (DateTimeParseException error) {
                        throw new IllegalArgumentException("fund history date is invalid", error);
                    }
                });
        if (result.size() < 21) throw new IllegalArgumentException("fund history has fewer than 21 rows");
        return List.copyOf(result);
    }

    private static Map<String, byte[]> unzipRequiredEntries(byte[] workbook) {
        var result = new HashMap<String, byte[]>();
        var total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var name = entry.getName();
                if (!"xl/sharedStrings.xml".equals(name) && !"xl/worksheets/sheet1.xml".equals(name)) continue;
                var output = new ByteArrayOutputStream();
                var buffer = new byte[8192];
                for (var read = zip.read(buffer); read >= 0; read = zip.read(buffer)) {
                    if (read == 0) continue;
                    total += read;
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw new IllegalArgumentException("fund workbook uncompressed data exceeded limit");
                    }
                    output.write(buffer, 0, read);
                }
                result.put(name, output.toByteArray());
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("fund workbook ZIP is invalid", error);
        }
        return result;
    }

    private static List<String> parseSharedStrings(byte[] xml) {
        var result = new ArrayList<String>();
        try {
            var reader = xmlFactory().createXMLStreamReader(new ByteArrayInputStream(xml));
            StringBuilder current = null;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "si".equals(reader.getLocalName())) {
                    current = new StringBuilder();
                } else if (event == XMLStreamConstants.START_ELEMENT && "t".equals(reader.getLocalName())
                        && current != null) {
                    current.append(reader.getElementText());
                } else if (event == XMLStreamConstants.END_ELEMENT && "si".equals(reader.getLocalName())) {
                    result.add(current == null ? "" : current.toString());
                    current = null;
                }
            }
            reader.close();
        } catch (XMLStreamException error) {
            throw new IllegalArgumentException("fund workbook shared strings are invalid", error);
        }
        return List.copyOf(result);
    }

    private static Map<Integer, Map<String, String>> parseRows(byte[] xml, List<String> sharedStrings) {
        var rows = new HashMap<Integer, Map<String, String>>();
        try {
            var reader = xmlFactory().createXMLStreamReader(new ByteArrayInputStream(xml));
            Integer rowNumber = null;
            Map<String, String> row = null;
            String column = null;
            String type = null;
            String value = null;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "row".equals(reader.getLocalName())) {
                    rowNumber = Integer.parseInt(reader.getAttributeValue(null, "r"));
                    row = new HashMap<>();
                } else if (event == XMLStreamConstants.START_ELEMENT && "c".equals(reader.getLocalName())) {
                    var reference = reader.getAttributeValue(null, "r");
                    column = reference == null ? null : reference.replaceAll("[0-9]", "");
                    type = reader.getAttributeValue(null, "t");
                    value = null;
                } else if (event == XMLStreamConstants.START_ELEMENT && "v".equals(reader.getLocalName())) {
                    value = reader.getElementText();
                } else if (event == XMLStreamConstants.END_ELEMENT && "c".equals(reader.getLocalName())) {
                    if (row != null && column != null && value != null) {
                        row.put(column, "s".equals(type) ? shared(sharedStrings, value) : value);
                    }
                    column = null;
                    type = null;
                    value = null;
                } else if (event == XMLStreamConstants.END_ELEMENT && "row".equals(reader.getLocalName())) {
                    if (rowNumber != null && row != null) rows.put(rowNumber, Map.copyOf(row));
                    rowNumber = null;
                    row = null;
                }
            }
            reader.close();
        } catch (XMLStreamException | NumberFormatException error) {
            throw new IllegalArgumentException("fund workbook sheet is invalid", error);
        }
        return Map.copyOf(rows);
    }

    private static XMLInputFactory xmlFactory() {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        return factory;
    }

    private static String shared(List<String> values, String index) {
        var parsed = Integer.parseInt(index);
        if (parsed < 0 || parsed >= values.size()) throw new IllegalArgumentException("shared string index is invalid");
        return values.get(parsed);
    }

    private static String value(Map<String, String> row, String column) {
        return row == null ? null : row.get(column);
    }

    private static byte[] required(Map<String, byte[]> entries, String key) {
        var value = entries.get(key);
        if (value == null) throw new IllegalArgumentException("fund workbook entry is missing: " + key);
        return value;
    }

    private static double positive(String raw, String field) {
        if (raw == null) throw new IllegalArgumentException(field + " is missing");
        var value = Double.parseDouble(raw);
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static boolean unavailable(String raw) {
        return raw == null || raw.isBlank() || "-".equals(raw.trim()) || "--".equals(raw.trim())
                || "N/A".equalsIgnoreCase(raw.trim());
    }

    private static boolean zeroCapitalization(String rawShares, String rawAssets) {
        try {
            return Double.parseDouble(rawShares) == 0d && Double.parseDouble(rawAssets) == 0d;
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
