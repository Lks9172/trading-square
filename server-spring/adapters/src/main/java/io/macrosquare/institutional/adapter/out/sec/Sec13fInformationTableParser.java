package io.macrosquare.institutional.adapter.out.sec;

import io.macrosquare.institutional.domain.model.InstitutionalHolding;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Sec13fInformationTableParser {

    private Sec13fInformationTableParser() {
    }

    static List<InstitutionalHolding> parse(byte[] xml) {
        var factory = XMLInputFactory.newFactory();
        safeProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        safeProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        safeProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            var holdings = new ArrayList<InstitutionalHolding>();
            MutableHolding current = null;
            String field = null;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    var local = reader.getLocalName();
                    if ("infoTable".equals(local)) current = new MutableHolding();
                    else if (current != null) field = local;
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && current != null && field != null && !reader.isWhiteSpace()) {
                    current.set(field, reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    var local = reader.getLocalName();
                    if ("infoTable".equals(local) && current != null) {
                        var holding = current.toHolding();
                        if (holding != null) holdings.add(holding);
                        current = null;
                    }
                    field = null;
                }
            }
            reader.close();
            return normalizeValueUnit(holdings);
        } catch (XMLStreamException error) {
            throw new IllegalArgumentException("SEC 13F information table is invalid XML", error);
        }
    }

    private static void safeProperty(XMLInputFactory factory, String key, boolean value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException ignored) {
            // The JDK provider supports these flags; alternative providers may omit one.
        }
    }

    /**
     * SEC's current XML specification says that {@code value} is reported in
     * dollars, but accepted filings still exist in which the same element uses
     * the historical thousands-of-dollars convention. The namespace and
     * schema version are identical in both variants. Detect that filing-level
     * basis break from implied value per share and normalize exactly once.
     */
    private static List<InstitutionalHolding> normalizeValueUnit(List<InstitutionalHolding> source) {
        var impliedPrices = source.stream()
                .filter(holding -> holding.valueUsd() > 0 && holding.shares() > 0)
                .map(holding -> holding.valueUsd() / holding.shares())
                .filter(Double::isFinite)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (impliedPrices.size() < 5) return List.copyOf(source);
        var median = impliedPrices.get(impliedPrices.size() / 2);
        var percentile90 = impliedPrices.get(Math.min(
                impliedPrices.size() - 1,
                (int) Math.ceil(impliedPrices.size() * 0.90) - 1
        ));
        var belowOne = impliedPrices.stream().filter(value -> value < 1).count();
        var historicalThousands = median < 1
                && percentile90 < 1
                && belowOne * 10 >= impliedPrices.size() * 9L;
        if (!historicalThousands) return List.copyOf(source);
        return source.stream().map(holding -> new InstitutionalHolding(
                holding.cusip(), holding.issuer(), holding.titleClass(), holding.putCall(),
                holding.valueUsd() * 1_000d, holding.shares()
        )).toList();
    }

    private static final class MutableHolding {
        private String issuer;
        private String titleClass;
        private String cusip;
        private String putCall;
        private Double reportedValue;
        private Double shares;

        private void set(String field, String raw) {
            var value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) return;
            switch (field) {
                case "nameOfIssuer" -> issuer = append(issuer, value);
                case "titleOfClass" -> titleClass = append(titleClass, value);
                case "cusip" -> cusip = append(cusip, value);
                case "putCall" -> putCall = append(putCall, value);
                // Current SEC 13F XML reports this field in US dollars. This
                // was cross-checked against current filing totals; do not
                // apply the legacy paper-form "thousands" convention here.
                case "value" -> reportedValue = positiveNumber(value);
                case "sshPrnamt" -> shares = positiveNumber(value);
                default -> {
                }
            }
        }

        private InstitutionalHolding toHolding() {
            if (cusip == null || cusip.isBlank() || issuer == null || issuer.isBlank()
                    || reportedValue == null || reportedValue <= 0
                    || shares == null || shares <= 0) return null;
            return new InstitutionalHolding(cusip, issuer, titleClass, putCall, reportedValue, shares);
        }

        private static String append(String existing, String value) {
            return existing == null ? value : existing + value;
        }

        private static Double positiveNumber(String value) {
            try {
                var parsed = Double.parseDouble(value.replace(",", ""));
                return Double.isFinite(parsed) && parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
