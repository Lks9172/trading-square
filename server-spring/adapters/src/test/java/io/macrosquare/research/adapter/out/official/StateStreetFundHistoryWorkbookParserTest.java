package io.macrosquare.research.adapter.out.official;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateStreetFundHistoryWorkbookParserTest {

    private final StateStreetFundHistoryWorkbookParser parser = new StateStreetFundHistoryWorkbookParser();

    @Test
    void parsesTheOfficialWorkbookShapeWithoutAHeavySpreadsheetDependency() throws Exception {
        var result = parser.parse(workbook("XLK"), "XLK");

        assertEquals(21, result.size());
        assertEquals(LocalDate.of(2026, 7, 1), result.getFirst().observedOn());
        assertEquals(120d, result.getLast().nav());
        assertEquals(1_200_000d, result.getLast().sharesOutstanding());
    }

    @Test
    void rejectsAWorkbookForAnotherFund() throws Exception {
        var workbook = workbook("XLF");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(workbook, "XLK"));
    }

    @Test
    void skipsIssuerPlaceholderRowsButKeepsCompleteObservations() throws Exception {
        var result = parser.parse(workbook("XLK", true), "XLK");

        assertEquals(21, result.size());
        assertEquals(LocalDate.of(2026, 7, 1), result.getFirst().observedOn());
    }

    @Test
    void skipsTheOfficialXlcPreLaunchZeroCapitalizationRow() throws Exception {
        var result = parser.parse(workbook("XLC", false, true, false), "XLC");

        assertEquals(21, result.size());
        assertEquals(LocalDate.of(2026, 7, 1), result.getFirst().observedOn());
    }

    @Test
    void rejectsPartialZeroCapitalizationInsteadOfHidingCorruptData() throws Exception {
        var value = workbook("XLC", false, false, true);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(value, "XLC"));
    }

    private static byte[] workbook(String ticker) throws Exception {
        return workbook(ticker, false, false, false);
    }

    private static byte[] workbook(String ticker, boolean placeholderRow) throws Exception {
        return workbook(ticker, placeholderRow, false, false);
    }

    private static byte[] workbook(
            String ticker,
            boolean placeholderRow,
            boolean zeroCapitalizationRow,
            boolean partialZeroCapitalizationRow
    ) throws Exception {
        var strings = new ArrayList<>(List.of(
                "Fund Name", ticker, "Date", "NAV", "Shares Outstanding", "Total Net Assets"));
        for (var index = 0; index < 21; index++) {
            strings.add(String.format(java.util.Locale.US, "%02d-Jul-2026", index + 1));
        }
        if (placeholderRow) strings.add("-");
        var shared = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        strings.forEach(value -> shared.append("<si><t>").append(value).append("</t></si>"));
        shared.append("</sst>");

        var sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                .append("<row r=\"2\"><c r=\"B2\" t=\"s\"><v>1</v></c></row>")
                .append("<row r=\"4\"><c r=\"A4\" t=\"s\"><v>2</v></c>")
                .append("<c r=\"B4\" t=\"s\"><v>3</v></c><c r=\"C4\" t=\"s\"><v>4</v></c>")
                .append("<c r=\"D4\" t=\"s\"><v>5</v></c></row>");
        if (placeholderRow) {
            var placeholderIndex = strings.size() - 1;
            sheet.append("<row r=\"5\"><c r=\"A5\" t=\"s\"><v>6")
                    .append("</v></c><c r=\"B5\" t=\"s\"><v>").append(placeholderIndex)
                    .append("</v></c><c r=\"C5\" t=\"s\"><v>").append(placeholderIndex)
                    .append("</v></c><c r=\"D5\" t=\"s\"><v>").append(placeholderIndex)
                    .append("</v></c></row>");
        }
        var extraRows = placeholderRow ? 1 : 0;
        if (zeroCapitalizationRow || partialZeroCapitalizationRow) {
            var row = 5 + extraRows;
            sheet.append("<row r=\"").append(row).append("\"><c r=\"A").append(row)
                    .append("\" t=\"s\"><v>6</v></c><c r=\"B").append(row)
                    .append("\"><v>50</v></c><c r=\"C").append(row)
                    .append("\"><v>0</v></c><c r=\"D").append(row).append("\"><v>")
                    .append(partialZeroCapitalizationRow ? "100" : "0")
                    .append("</v></c></row>");
            extraRows++;
        }
        for (var index = 0; index < 21; index++) {
            var row = index + 5 + extraRows;
            var nav = 100 + index;
            var shares = 1_000_000 + index * 10_000;
            sheet.append("<row r=\"").append(row).append("\"><c r=\"A").append(row)
                    .append("\" t=\"s\"><v>").append(6 + index).append("</v></c>")
                    .append("<c r=\"B").append(row).append("\"><v>").append(nav).append("</v></c>")
                    .append("<c r=\"C").append(row).append("\"><v>").append(shares).append("</v></c>")
                    .append("<c r=\"D").append(row).append("\"><v>").append((long) nav * shares)
                    .append("</v></c></row>");
        }
        sheet.append("</sheetData></worksheet>");

        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            entry(zip, "xl/sharedStrings.xml", shared.toString());
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
