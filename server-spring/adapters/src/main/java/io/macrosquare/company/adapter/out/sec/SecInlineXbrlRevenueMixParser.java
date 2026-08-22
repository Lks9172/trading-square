package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixFact;
import io.macrosquare.company.domain.model.CompanyRevenueTotal;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Secure, bounded streaming parser for the subset of Inline XBRL needed by revenue mix. */
final class SecInlineXbrlRevenueMixParser {

    private static final int MAX_CONTEXTS = 25_000;
    private static final int MAX_REVENUE_FACTS = 4_000;
    private static final Set<String> REVENUE_CONCEPTS = Set.of(
            "revenue",
            "revenues",
            "revenuefromcontractwithcustomerexcludingassessedtax",
            "revenuefromcontractswithcustomers",
            "salesrevenuenet",
            "salesrevenuegoodsnet",
            "salesrevenueservicesnet",
            "revenuefromexternalcustomers",
            "salesrevenuegoodsnetofreturnsandallowances",
            "netrevenue"
    );
    private static final Map<String, String> MEMBER_LABELS = memberLabels();

    private SecInlineXbrlRevenueMixParser() {
    }

    static CompanyRevenueMixEvidence parse(byte[] bytes, String source) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Inline XBRL document is empty");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");

        var contexts = new HashMap<String, FilingContext>();
        var observations = new ArrayList<RawRevenueFact>();
        var factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);

        try (var input = new ByteArrayInputStream(bytes)) {
            var reader = factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());
            try {
                while (reader.hasNext()) {
                    var event = reader.next();
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    var localName = reader.getLocalName();
                    if ("context".equalsIgnoreCase(localName)) {
                        var id = attribute(reader, "id");
                        var context = parseContext(reader);
                        if (id != null && context != null) {
                            if (contexts.size() >= MAX_CONTEXTS && !contexts.containsKey(id)) {
                                throw new IllegalArgumentException("Inline XBRL context limit exceeded");
                            }
                            contexts.put(id, context);
                        }
                    } else if ("nonfraction".equalsIgnoreCase(localName)) {
                        var fact = parseRevenueFact(reader);
                        if (fact != null) {
                            if (observations.size() >= MAX_REVENUE_FACTS) {
                                throw new IllegalArgumentException("Inline XBRL revenue fact limit exceeded");
                            }
                            observations.add(fact);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException error) {
            throw new IllegalArgumentException("Inline XBRL XML could not be parsed", error);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Unable to close in-memory Inline XBRL input", impossible);
        }

        var facts = new ArrayList<CompanyRevenueMixFact>();
        var totals = new ArrayList<CompanyRevenueTotal>();
        for (var observation : observations) {
            var context = contexts.get(observation.contextReference());
            if (context == null || context.periodStart() == null || context.periodEnd() == null) continue;
            if (context.dimensions().isEmpty()) {
                totals.add(new CompanyRevenueTotal(
                        observation.value(), observation.unit(), context.periodStart(), context.periodEnd()
                ));
                continue;
            }
            var targetDimensions = context.dimensions().stream()
                    .map(SecInlineXbrlRevenueMixParser::semanticDimension)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            var unsupportedDimensions = context.dimensions().stream()
                    .filter(dimension -> semanticDimension(dimension) == null)
                    .filter(dimension -> !isNeutralDimension(dimension))
                    .count();
            if (unsupportedDimensions > 0) continue;
            var target = selectSemanticDimension(targetDimensions);
            if (target == null) continue;
            facts.add(new CompanyRevenueMixFact(
                    target.kind(),
                    humanize(target.dimension()),
                    humanizeMember(target.member()),
                    observation.value(),
                    observation.unit(),
                    context.periodStart(),
                    context.periodEnd()
            ));
        }
        return new CompanyRevenueMixEvidence(source, facts, totals.stream().distinct().toList());
    }

    private static FilingContext parseContext(XMLStreamReader reader) throws XMLStreamException {
        LocalDate start = null;
        LocalDate end = null;
        var dimensions = new ArrayList<RawDimension>();
        var depth = 1;
        while (reader.hasNext() && depth > 0) {
            var event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                var localName = reader.getLocalName();
                if ("startdate".equalsIgnoreCase(localName)) {
                    start = parseDate(readSimpleText(reader));
                    depth--;
                } else if ("enddate".equalsIgnoreCase(localName)) {
                    end = parseDate(readSimpleText(reader));
                    depth--;
                } else if ("explicitmember".equalsIgnoreCase(localName)) {
                    var dimension = attribute(reader, "dimension");
                    var member = readSimpleText(reader);
                    depth--;
                    if (dimension != null && member != null && !member.isBlank()) {
                        dimensions.add(new RawDimension(dimension, member.trim()));
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new FilingContext(start, end, List.copyOf(dimensions));
    }

    private static RawRevenueFact parseRevenueFact(XMLStreamReader reader) throws XMLStreamException {
        var concept = attribute(reader, "name");
        var contextReference = attribute(reader, "contextref");
        var unitReference = attribute(reader, "unitref");
        var scale = parseScale(attribute(reader, "scale"));
        var sign = attribute(reader, "sign");
        var format = attribute(reader, "format");
        var nil = attribute(reader, "nil");
        var text = readFactText(reader);
        if (concept == null || !REVENUE_CONCEPTS.contains(localPart(concept).toLowerCase(Locale.ROOT))) return null;
        if (contextReference == null || unitReference == null || "true".equalsIgnoreCase(nil)) return null;
        var value = parseNumber(text, format, scale, sign);
        if (value == null || value.signum() <= 0) return null;
        return new RawRevenueFact(contextReference, normalizeUnit(unitReference), value);
    }

    private static String readFactText(XMLStreamReader reader) throws XMLStreamException {
        var value = new StringBuilder();
        var depth = 1;
        var excludedDepth = 0;
        while (reader.hasNext() && depth > 0) {
            var event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if ("exclude".equalsIgnoreCase(reader.getLocalName())) excludedDepth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("exclude".equalsIgnoreCase(reader.getLocalName()) && excludedDepth > 0) excludedDepth--;
                depth--;
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && excludedDepth == 0) {
                value.append(reader.getText());
            }
        }
        return value.toString();
    }

    private static String readSimpleText(XMLStreamReader reader) throws XMLStreamException {
        var value = new StringBuilder();
        var depth = 1;
        while (reader.hasNext() && depth > 0) {
            var event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
            else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                value.append(reader.getText());
            }
        }
        return value.toString().trim();
    }

    private static SemanticDimension semanticDimension(RawDimension dimension) {
        var axis = localPart(dimension.dimension()).toLowerCase(Locale.ROOT);
        if (axis.contains("geograph") || axis.contains("country") || axis.contains("regionaxis")) {
            return new SemanticDimension(
                    CompanyRevenueMixDimension.GEOGRAPHY, dimension.dimension(), dimension.member()
            );
        }
        if (axis.contains("statementbusinesssegments") || axis.contains("reportablesegments")
                || axis.contains("operatingsegments") || axis.contains("businesssegmentaxis")) {
            return new SemanticDimension(
                    CompanyRevenueMixDimension.REPORTABLE_SEGMENT, dimension.dimension(), dimension.member()
            );
        }
        if (axis.contains("productorservice") || axis.contains("productsandservices")
                || axis.contains("productserviceaxis") || axis.contains("marketsofcustomers")
                || axis.contains("customermarket") || axis.contains("endmarket")) {
            return new SemanticDimension(
                    CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, dimension.dimension(), dimension.member()
            );
        }
        return null;
    }

    /**
     * Accepts a single semantic axis directly. A narrowly defined two-axis
     * exception handles filings that tag a reportable segment or geography
     * together with a product-axis member meaning total operating revenue.
     * Other cross-tabs stay rejected so customer, product and segment values
     * cannot be mixed or double-counted accidentally.
     */
    private static SemanticDimension selectSemanticDimension(List<SemanticDimension> dimensions) {
        if (dimensions.size() == 1) return dimensions.getFirst();
        if (dimensions.size() != 2) return null;
        var qualifier = dimensions.stream()
                .filter(dimension -> dimension.kind() == CompanyRevenueMixDimension.PRODUCT_OR_SERVICE)
                .filter(SecInlineXbrlRevenueMixParser::isAggregateRevenueQualifier)
                .findFirst()
                .orElse(null);
        if (qualifier == null) return null;
        return dimensions.stream()
                .filter(dimension -> dimension != qualifier)
                .filter(dimension -> dimension.kind() != CompanyRevenueMixDimension.PRODUCT_OR_SERVICE)
                .findFirst()
                .orElse(null);
    }

    private static boolean isAggregateRevenueQualifier(SemanticDimension dimension) {
        var member = localPart(dimension.member()).toLowerCase(Locale.ROOT)
                .replaceFirst("member$", "");
        return member.equals("salesandotheroperatingrevenue")
                || member.equals("totalrevenue")
                || member.equals("totalnetrevenue")
                || member.equals("netrevenue")
                || member.equals("revenues")
                || member.equals("revenue");
    }

    private static boolean isNeutralDimension(RawDimension dimension) {
        var axis = localPart(dimension.dimension()).toLowerCase(Locale.ROOT);
        var member = localPart(dimension.member()).toLowerCase(Locale.ROOT);
        return axis.contains("consolidationitems")
                && (member.contains("operatingsegments") || member.contains("reportablesegments"));
    }

    private static BigDecimal parseNumber(String raw, String format, int scale, String sign) {
        if (raw == null || raw.isBlank()) return null;
        var value = raw.replace('\u00a0', ' ').trim();
        var negative = value.startsWith("(") && value.endsWith(")");
        var commaDecimal = format != null && format.toLowerCase(Locale.ROOT).contains("num-comma-decimal");
        if (commaDecimal) {
            value = value.replace(".", "").replace(',', '.');
        } else {
            value = value.replace(",", "");
        }
        value = value.replaceAll("[^0-9.\\-]", "");
        if (value.isBlank() || "-".equals(value) || ".".equals(value)) return null;
        try {
            var number = new BigDecimal(value);
            if (negative || "-".equals(sign)) number = number.abs().negate();
            return number.scaleByPowerOfTen(scale);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int parseScale(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            var parsed = Integer.parseInt(value);
            if (parsed < -18 || parsed > 18) throw new IllegalArgumentException("Inline XBRL scale is out of range");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Inline XBRL scale is invalid", error);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static String attribute(XMLStreamReader reader, String expected) {
        for (var index = 0; index < reader.getAttributeCount(); index++) {
            if (expected.equalsIgnoreCase(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static String normalizeUnit(String value) {
        var local = localPart(value);
        return local.toLowerCase(Locale.ROOT).contains("usd") ? "USD" : local.toUpperCase(Locale.ROOT);
    }

    private static String humanizeMember(String value) {
        var local = localPart(value);
        var withoutSuffix = local
                .replaceFirst("(?i)SegmentMember$", "")
                .replaceFirst("(?i)Member$", "");
        var mapped = MEMBER_LABELS.get(withoutSuffix.toUpperCase(Locale.ROOT));
        return mapped == null ? humanize(withoutSuffix) : mapped;
    }

    private static String humanize(String value) {
        var local = localPart(value)
                .replaceFirst("(?i)Axis$", "")
                .replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("(?i)\\bAnd\\b", "and")
                .replaceAll("\\s+", " ")
                .trim();
        return local.isBlank() ? value : local;
    }

    private static String localPart(String value) {
        var separator = value.indexOf(':');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // Provider does not support the optional hardening property. The
            // remaining supported DTD/external-entity controls still apply.
        }
    }

    private static Map<String, String> memberLabels() {
        var labels = new LinkedHashMap<String, String>();
        labels.put("US", "United States");
        labels.put("GB", "United Kingdom");
        labels.put("DE", "Germany");
        labels.put("JP", "Japan");
        labels.put("CN", "China");
        labels.put("TW", "Taiwan");
        labels.put("KR", "South Korea");
        labels.put("NL", "Netherlands");
        labels.put("SG", "Singapore");
        labels.put("IL", "Israel");
        labels.put("CA", "Canada");
        labels.put("MX", "Mexico");
        labels.put("NONUS", "Non-U.S.");
        labels.put("IPHONE", "iPhone");
        labels.put("IPAD", "iPad");
        labels.put("EMEA", "EMEA");
        labels.put("APAC", "APAC");
        labels.put("OEMANDOTHER", "OEM and Other");
        return Map.copyOf(labels);
    }

    private record RawDimension(String dimension, String member) {
    }

    private record FilingContext(
            LocalDate periodStart,
            LocalDate periodEnd,
            List<RawDimension> dimensions
    ) {
    }

    private record RawRevenueFact(String contextReference, String unit, BigDecimal value) {
    }

    private record SemanticDimension(
            CompanyRevenueMixDimension kind,
            String dimension,
            String member
    ) {
    }
}
