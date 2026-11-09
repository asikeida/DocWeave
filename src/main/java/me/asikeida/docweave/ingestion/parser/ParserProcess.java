package me.asikeida.docweave.ingestion.parser;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.*;

/** 单并发受限子 JVM；调用者提供受控文件流、预期长度和摘要，所有暂存由本类创建和回收。 */
public final class ParserProcess {
    private final Semaphore slot = new Semaphore(1);
    private final Path root;
    private final Duration timeout;
    private volatile boolean unavailable;

    public ParserProcess(Path root, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Parser timeout must be in (0, 60s]");
        }
        this.root = root.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    /** 不关闭调用者的输入流；不信任后缀、客户端摘要或外部提供的物理路径。 */
    public ParsedDocument parse(InputStream input, long expectedBytes, String expectedSha256, String mime) throws IOException {
        if (unavailable) throw new ParsingFailure(PARSER_UNAVAILABLE);
        if (expectedBytes <= 0 || expectedBytes > DocumentTextParser.MAX_FILE_BYTES
                || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")
                || mime == null || !Set.of("application/pdf", "text/plain", "text/markdown").contains(mime)) throw new ParsingFailure(INVALID_FILE);
        if (!slot.tryAcquire()) throw new ParsingFailure(PARSER_BUSY);
        Path work = null;
        try {
            checkRoot();
            work = Files.createTempDirectory(root, "attempt-");
            if (Files.getFileStore(work).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------"));
            }
            Path source = work.resolve("input.bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (OutputStream out = Files.newOutputStream(source, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[16384];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    count += length;
                    if (count > expectedBytes) throw new ParsingFailure(INVALID_FILE);
                    digest.update(buffer, 0, length);
                    out.write(buffer, 0, length);
                }
            }
            if (count != expectedBytes || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) throw new ParsingFailure(INVALID_FILE);
            ProcessSupervisor.run(command(work, mime), work, timeout);
            return ParserResultFile.read(work.resolve("result.bin"));
        } catch (ParsingFailure e) {
            if (e.code() == PARSER_TERMINATION_FAILED) {
                // 未确认退出就不能清理它正在用的目录或继续启动更多执行器。
                unavailable = true;
                work = null;
            }
            throw e;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            try { if (work != null) cleanup(work); }
            finally { slot.release(); }
        }
    }

    private void checkRoot() throws IOException {
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new ParsingFailure(PARSER_UNAVAILABLE);
        }
        Files.createDirectories(root);
    }

    private static List<String> command(Path work, String mime) throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-XX:MaxDirectMemorySize=16m", "-XX:+ExitOnOutOfMemoryError",
                "-Djava.io.tmpdir=" + work, "-Duser.home=" + work, "-Djava.awt.headless=true"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path candidate = Path.of(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))[0]).toAbsolutePath();
        if (!classpath.contains(File.pathSeparator) && Files.isRegularFile(candidate) && candidate.toString().endsWith(".jar")) {
            // java -jar 的 classpath 是应用 JAR；IDE/测试模式使用绝对 classpath。
            command.addAll(List.of("-jar", candidate.toString(), "--parse-document"));
        } else {
            String absolute = Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                    .map(value -> Path.of(value).toAbsolutePath().normalize().toString())
                    .collect(java.util.stream.Collectors.joining(File.pathSeparator));
            command.addAll(List.of("-cp", absolute, ParserMain.class.getName()));
        }
        command.addAll(List.of(work.resolve("input.bin").toString(), mime, work.resolve("result.bin").toString()));
        return command;
    }

    private void cleanup(Path work) throws IOException {
        if (!work.getParent().equals(root) || !work.getFileName().toString().startsWith("attempt-")) throw new ParsingFailure(PARSER_UNAVAILABLE);
        // walk 不跟随符号链接，只清理由本次调用创建的 attempt 目录。
        List<Path> targets;
        try (var paths = Files.walk(work)) { targets = paths.sorted(Comparator.reverseOrder()).toList(); }
        // Windows 不允许删除仍被遍历句柄打开的目录；必须先关闭 walk。
        boolean interrupted = Thread.interrupted();
        try {
            for (Path path : targets) {
                for (int attempt = 0; ; attempt++) {
                    try { Files.deleteIfExists(path); break; }
                    catch (FileSystemException e) {
                        // Windows 的进程退出信号可能早于工作目录句柄释放；仅做有界重试。
                        if (attempt >= 19) throw e;
                        try { Thread.sleep(50); }
                        catch (InterruptedException cancellation) { interrupted = true; }
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
