package me.asikeida.docweave.ingestion.parser;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.INVALID_PARSER_RESULT;

/** 有版本、有大小边界的内部二进制协议；只有完整文件才原子发布，不使用 Java 反序列化。 */
final class ParserResultFile {
    private static final int MAGIC = 0x44575031;
    private static final long MAX_RESULT_BYTES = DocumentTextParser.MAX_TEXT_BYTES + 4096L * 28 + 256;

    static void write(Path output, ParsedDocument result, ParsingFailure.Code failure) throws IOException {
        Path temporary = output.resolveSibling("result.part");
        try {
            try (var out = new DataOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW))) {
                out.writeInt(MAGIC);
                out.writeUTF(failure == null ? "OK" : failure.name());
                if (failure == null) {
                    byte[] text = result.text().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(text.length);
                    out.write(text);
                    out.writeInt(result.locations().size());
                    for (var location : result.locations()) {
                        out.writeInt(location.sequence());
                        out.writeInt(number(location.page()));
                        out.writeInt(number(location.startLine()));
                        out.writeInt(number(location.endLine()));
                        out.writeInt(location.start());
                        out.writeInt(location.end());
                    }
                }
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static ParsedDocument read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_RESULT_BYTES) {
            throw new ParsingFailure(INVALID_PARSER_RESULT);
        }
        try (var in = new DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            if (in.readInt() != MAGIC) throw new ParsingFailure(INVALID_PARSER_RESULT);
            String status = in.readUTF();
            if (!"OK".equals(status)) {
                var code = ParsingFailure.Code.valueOf(status);
                if (in.read() != -1) throw new ParsingFailure(INVALID_PARSER_RESULT);
                throw new ParsingFailure(code);
            }
            int bytes = in.readInt();
            if (bytes <= 0 || bytes > DocumentTextParser.MAX_TEXT_BYTES) throw new ParsingFailure(INVALID_PARSER_RESULT);
            byte[] raw = in.readNBytes(bytes);
            if (raw.length != bytes) throw new ParsingFailure(INVALID_PARSER_RESULT);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            int count = in.readInt();
            if (count < 1 || count > DocumentTextParser.MAX_LOCATIONS) throw new ParsingFailure(INVALID_PARSER_RESULT);
            var locations = new ArrayList<ParsedDocument.Location>();
            int previousEnd = 0;
            for (int i = 0; i < count; i++) {
                int sequence = in.readInt(), page = in.readInt(), first = in.readInt(), last = in.readInt();
                int start = in.readInt(), end = in.readInt();
                if (sequence != i || start < previousEnd || end < start || end > text.length()
                        || splitsSurrogate(text, start) || splitsSurrogate(text, end)
                        || (page == -1 ? first < 1 || last < first : page < 1 || page > 500 || first != -1 || last != -1)
                        || text.substring(start, end).getBytes(StandardCharsets.UTF_8).length > DocumentTextParser.MAX_PART_BYTES) {
                    throw new ParsingFailure(INVALID_PARSER_RESULT);
                }
                locations.add(new ParsedDocument.Location(sequence, nullable(page), nullable(first), nullable(last), start, end));
                previousEnd = end;
            }
            if (previousEnd != text.length() || in.read() != -1 || text.isBlank()) throw new ParsingFailure(INVALID_PARSER_RESULT);
            return new ParsedDocument(text, locations);
        } catch (ParsingFailure e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new ParsingFailure(INVALID_PARSER_RESULT);
        }
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static int number(Integer value) { return value == null ? -1 : value; }
    private static Integer nullable(int value) { return value == -1 ? null : value; }
}
