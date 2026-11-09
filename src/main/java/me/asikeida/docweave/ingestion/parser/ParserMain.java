package me.asikeida.docweave.ingestion.parser;

import java.io.IOException;
import java.nio.file.*;

/** 同一应用 JAR 的无 Spring 解析入口；只向父进程发布结果文件，不连接数据库或启动 Web。 */
public final class ParserMain {
    private ParserMain() { }

    public static void main(String[] args) {
        if (args.length != 3) System.exit(64);
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!input.getFileName().toString().equals("input.bin") || !output.getFileName().toString().equals("result.bin")
                || !input.getParent().equals(output.getParent()) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            System.exit(64);
        }
        var parent = ProcessHandle.current().parent();
        ParserWatchdog.start(java.time.Duration.ofSeconds(60), () -> parent.map(ProcessHandle::isAlive).orElse(true));
        ParsedDocument parsed = null;
        ParsingFailure.Code failure = null;
        try {
            parsed = new DocumentTextParser().parse(input, args[1]);
        } catch (ParsingFailure e) {
            failure = e.code();
        } catch (IOException | RuntimeException e) {
            failure = ParsingFailure.Code.INVALID_FILE;
        }
        try {
            ParserResultFile.write(output, parsed, failure);
        } catch (IOException e) {
            System.exit(74);
        }
    }
}
