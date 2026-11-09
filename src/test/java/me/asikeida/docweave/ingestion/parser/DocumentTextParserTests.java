package me.asikeida.docweave.ingestion.parser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.*;

class DocumentTextParserTests {
    @TempDir Path directory;
    private final DocumentTextParser parser = new DocumentTextParser();

    @Test void normalizesUtf8BomNewlinesAndPreservesEmojiOffsets() throws Exception {
        var parsed = parse("\uFEFF中文😀\r\n第二行\r<script>保留文字</script>\n", "text/markdown");
        assertEquals("中文😀\n第二行\n<script>保留文字</script>\n", parsed.text());
        var position = parsed.locations().getFirst();
        assertEquals(1, position.startLine()); assertEquals(3, position.endLine());
        assertEquals(parsed.text(), parsed.text().substring(position.start(), position.end()));
        assertFalse(parsed.toString().contains("保留文字"));
    }

    @Test void splitsLongLinesWithoutBreakingUnicodeOrByteLimits() throws Exception {
        String body = "😀中".repeat(20000) + "\n末行";
        var parsed = parse(body, "text/plain");
        assertTrue(parsed.locations().size() > 1);
        StringBuilder restored = new StringBuilder();
        for (var location : parsed.locations()) {
            String part = parsed.text().substring(location.start(), location.end());
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= DocumentTextParser.MAX_PART_BYTES);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            restored.append(part);
        }
        assertEquals(body, restored.toString());
        assertEquals(2, parsed.locations().getLast().endLine());
    }

    @Test void rejectsInvalidEncodingWhitespaceAndOversizedOutput() throws Exception {
        Path input = directory.resolve("bad.bin"); Files.write(input, new byte[]{(byte)0xc3, 0x28});
        assertEquals(INVALID_TEXT, assertThrows(ParsingFailure.class, () -> parser.parse(input, "text/plain")).code());
        assertEquals(NO_EXTRACTABLE_TEXT, assertThrows(ParsingFailure.class, () -> parse(" \t\r\n", "text/plain")).code());
        assertEquals(INVALID_TEXT, assertThrows(ParsingFailure.class, () -> parse("abc\u0000", "text/plain")).code());
        assertEquals(TEXT_TOO_LARGE, assertThrows(ParsingFailure.class, () -> parse("a".repeat(DocumentTextParser.MAX_TEXT_BYTES + 1), "text/plain")).code());
        assertEquals(UNSUPPORTED_FILE_TYPE, assertThrows(ParsingFailure.class, () -> parse("abc", "text/html")).code());
    }

    @Test void extractsPdfPagesIncludingAnEmptyMiddlePage() throws Exception {
        Path input = pdf("First page", "", "Third page");
        var parsed = parser.parse(input, "application/pdf");
        assertEquals(3, parsed.locations().size());
        assertTrue(parsed.text().substring(parsed.locations().getFirst().start(), parsed.locations().getFirst().end()).contains("First page"));
        var blank = parsed.locations().get(1);
        assertEquals(2, blank.page()); assertEquals(blank.start(), blank.end());
        assertEquals(3, parsed.locations().getLast().page());
        assertTrue(parsed.text().contains("\n\f\n"));
    }

    @Test void rejectsEmptyEncryptedAndTooManyPdfPages() throws Exception {
        assertEquals(NO_EXTRACTABLE_TEXT, assertThrows(ParsingFailure.class, () -> parser.parse(pdf(""), "application/pdf")).code());
        Path encrypted = directory.resolve("encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "reader", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(encrypted.toFile());
        }
        assertEquals(ENCRYPTED_PDF, assertThrows(ParsingFailure.class, () -> parser.parse(encrypted, "application/pdf")).code());
        String[] pages = new String[501]; Arrays.fill(pages, "");
        assertEquals(PDF_PAGE_LIMIT, assertThrows(ParsingFailure.class, () -> parser.parse(pdf(pages), "application/pdf")).code());
        assertEquals(INVALID_FILE, assertThrows(ParsingFailure.class, () -> parse("%PDF-broken", "application/pdf")).code());
    }

    @Test void resultProtocolRejectsTruncationTrailingBytesAndInvalidOffsets() throws Exception {
        var parsed = parse("中文😀\n", "text/plain");
        Path result = directory.resolve("result.bin");
        ParserResultFile.write(result, parsed, null);
        assertEquals(parsed, ParserResultFile.read(result));
        Files.write(result, new byte[]{1}, StandardOpenOption.APPEND);
        assertEquals(INVALID_PARSER_RESULT, assertThrows(ParsingFailure.class, () -> ParserResultFile.read(result)).code());
        Files.delete(result);
        ParserResultFile.write(result, new ParsedDocument("😀", java.util.List.of(new ParsedDocument.Location(0, null, 1, 1, 1, 2))), null);
        assertEquals(INVALID_PARSER_RESULT, assertThrows(ParsingFailure.class, () -> ParserResultFile.read(result)).code());
        Files.write(result, new byte[]{1,2});
        assertEquals(INVALID_PARSER_RESULT, assertThrows(ParsingFailure.class, () -> ParserResultFile.read(result)).code());
    }

    private ParsedDocument parse(String body, String mime) throws Exception {
        Path input = Files.createTempFile(directory, "source", ".bin");
        Files.writeString(input, body, StandardCharsets.UTF_8);
        return parser.parse(input, mime);
    }

    private Path pdf(String... pages) throws Exception {
        Path path = Files.createTempFile(directory, "fixture", ".pdf");
        try (var document = new PDDocument()) {
            for (String body : pages) {
                var page = new PDPage(); document.addPage(page);
                if (!body.isEmpty()) try (var out = new PDPageContentStream(document, page)) {
                    out.beginText(); out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    out.newLineAtOffset(20, 700); out.showText(body); out.endText();
                }
            }
            document.save(path.toFile());
        }
        return path;
    }
}
