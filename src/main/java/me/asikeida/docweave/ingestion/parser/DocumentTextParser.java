package me.asikeida.docweave.ingestion.parser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.*;

/** 有界正文提取，只在独立解析 JVM 中处理不可信文件；Markdown 保留纯文本。 */
public final class DocumentTextParser {
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PART_BYTES = 48 * 1024;
    public static final int MAX_PAGES = 500;
    public static final int MAX_LOCATIONS = 4096;

    public ParsedDocument parse(Path input, String mime) throws IOException {
        if (!Files.isRegularFile(input, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(input) == 0 || Files.size(input) > MAX_FILE_BYTES) {
            throw new ParsingFailure(INVALID_FILE);
        }
        try {
            return switch (mime) {
                case "application/pdf" -> pdf(input);
                case "text/plain", "text/markdown" -> text(input);
                default -> throw new ParsingFailure(UNSUPPORTED_FILE_TYPE);
            };
        } catch (ParsingFailure e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new ParsingFailure(ENCRYPTED_PDF);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ParsingFailure(INVALID_TEXT);
        } catch (IOException | RuntimeException e) {
            throw new ParsingFailure(INVALID_FILE);
        }
    }

    private ParsedDocument text(Path input) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var text = new BoundedText();
        try (Reader reader = new java.io.InputStreamReader(Files.newInputStream(input), decoder)) {
            char[] buffer = new char[4096];
            int size;
            while ((size = reader.read(buffer)) != -1) text.write(buffer, 0, size);
        }
        String normalized = normalize(text.toString());
        var locations = new ArrayList<ParsedDocument.Location>();
        appendLocations(normalized, 0, null, 1, locations);
        return result(normalized, locations);
    }

    private ParsedDocument pdf(Path input) throws IOException {
        try (var document = Loader.loadPDF(input.toFile())) {
            if (document.isEncrypted()) throw new ParsingFailure(ENCRYPTED_PDF);
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > MAX_PAGES) {
                throw new ParsingFailure(PDF_PAGE_LIMIT);
            }
            var full = new BoundedText();
            var locations = new ArrayList<ParsedDocument.Location>();
            var stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setPageStart("");
            stripper.setPageEnd("");
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var extracted = new BoundedText();
                stripper.writeText(document, extracted);
                String body = normalize(extracted.toString());
                if (page > 1) full.write("\n\f\n");
                int start = full.length();
                full.write(body);
                appendLocations(body, start, page, null, locations);
            }
            return result(full.toString(), locations);
        }
    }

    private static ParsedDocument result(String text, List<ParsedDocument.Location> locations) throws IOException {
        if (text.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
            throw new ParsingFailure(NO_EXTRACTABLE_TEXT);
        }
        return new ParsedDocument(text, locations);
    }

    private static String normalize(String text) throws IOException {
        String value = text.startsWith("\uFEFF") ? text.substring(1) : text;
        value = value.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 && c != '\n' && c != '\t') || c == 127) throw new ParsingFailure(INVALID_TEXT);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new ParsingFailure(INVALID_TEXT);
            } else if (Character.isLowSurrogate(c)) throw new ParsingFailure(INVALID_TEXT);
        }
        return value;
    }

    private static void appendLocations(String body, int offset, Integer page, Integer firstLine,
                                         List<ParsedDocument.Location> target) throws IOException {
        int start = 0, line = firstLine == null ? 0 : firstLine;
        do {
            int end = start, bytes = 0, endLine = line;
            while (end < body.length()) {
                int cp = body.codePointAt(end);
                int width = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                if (bytes + width > MAX_PART_BYTES) break;
                bytes += width;
                if (cp == '\n') endLine++;
                end += Character.charCount(cp);
            }
            if (target.size() >= MAX_LOCATIONS) throw new ParsingFailure(TEXT_TOO_LARGE);
            target.add(new ParsedDocument.Location(target.size(), page, page == null ? line : null,
                    page == null ? Math.max(line, endLine - (end > start && body.charAt(end - 1) == '\n' ? 1 : 0)) : null,
                    offset + start, offset + end));
            start = end;
            line = endLine;
        } while (start < body.length());
    }

    /** 提取过程中即限制缓冲，不先把任意大小的 PDF 文本读入堆再检查。 */
    private static final class BoundedText extends Writer {
        private final StringBuilder value = new StringBuilder();
        private int bytes;
        private boolean highSurrogate;

        @Override public void write(char[] chars, int offset, int count) throws IOException {
            for (int i = offset; i < offset + count; i++) {
                char c = chars[i];
                bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : Character.isLowSurrogate(c) && highSurrogate ? 1 : 3;
                highSurrogate = Character.isHighSurrogate(c);
                if (bytes > MAX_TEXT_BYTES) throw new ParsingFailure(TEXT_TOO_LARGE);
                value.append(c);
            }
        }

        int length() { return value.length(); }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return value.toString(); }
    }
}
