package me.asikeida.docweave.ingestion.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.*;

/** 只管理本次创建的进程；终止后确认退出，不能把发出 kill 请求当成已释放资源。 */
final class ProcessSupervisor {
    static void run(List<String> command, Path directory, Duration timeout) throws IOException {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        var inherited = System.getenv();
        builder.environment().clear();
        // Windows 创建进程需要系统目录；不传 PATH、代理、JVM 注入参数或业务密钥。
        for (String key : List.of("SystemRoot", "WINDIR")) {
            if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
        }
        builder.environment().put("HOME", directory.toString());
        builder.environment().put("TMP", directory.toString());
        builder.environment().put("TEMP", directory.toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        try {
            java.nio.file.Files.writeString(directory.resolve("child.owner"), child.pid() + ":"
                    + child.info().startInstant().orElse(java.time.Instant.EPOCH).toEpochMilli());
            child.getOutputStream().close();
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new ParsingFailure(PARSER_TIMEOUT);
            if (child.exitValue() == 124) throw new ParsingFailure(PARSER_TIMEOUT);
            if (child.exitValue() != 0) throw new ParsingFailure(PARSER_PROCESS_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParsingFailure(PARSER_UNAVAILABLE);
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (child.isAlive()) {
                    child.destroy();
                    if (!awaitExit(child, Duration.ofSeconds(2))) {
                        child.destroyForcibly();
                        if (!awaitExit(child, Duration.ofSeconds(5))) throw new ParsingFailure(PARSER_TERMINATION_FAILED);
                    }
                }
            } finally {
                try { child.getInputStream().close(); } catch (IOException ignored) { }
                try { child.getErrorStream().close(); } catch (IOException ignored) { }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitExit(Process child, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            while (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try { child.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
