package me.asikeida.docweave.ingestion.parser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static me.asikeida.docweave.ingestion.parser.ParsingFailure.Code.*;

class ParserProcessTests {
    @TempDir Path root;

    @Test void childJvmParsesAndCleansPrivateDirectory() throws Exception {
        byte[] input = "第一行😀\r\n第二行".getBytes(StandardCharsets.UTF_8);
        var runner = new ParserProcess(root, Duration.ofSeconds(15));
        var result = runner.parse(new ByteArrayInputStream(input), input.length, hash(input), "text/plain");
        assertEquals("第一行😀\n第二行", result.text());
        assertEquals(2, result.locations().getFirst().endLine());
        assertEmpty();
    }

    @Test void wrongLengthAndDigestNeverPublishResults() throws Exception {
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        var runner = new ParserProcess(root, Duration.ofSeconds(15));
        assertEquals(INVALID_FILE, assertThrows(ParsingFailure.class,
                () -> runner.parse(new ByteArrayInputStream(input), 2, hash(input), "text/plain")).code());
        assertEquals(INVALID_FILE, assertThrows(ParsingFailure.class,
                () -> runner.parse(new ByteArrayInputStream(input), 3, "0".repeat(64), "text/plain")).code());
        assertEmpty();
    }

    @Test void timeoutAndChildFailureReleaseSlotAndTemporaryFiles() throws Exception {
        byte[] input = "hello".getBytes(StandardCharsets.UTF_8);
        var timed = new ParserProcess(root, Duration.ofMillis(1));
        assertEquals(PARSER_TIMEOUT, assertThrows(ParsingFailure.class,
                () -> timed.parse(new ByteArrayInputStream(input), input.length, hash(input), "text/plain")).code());
        assertEmpty();
        var normal = new ParserProcess(root, Duration.ofSeconds(15));
        byte[] invalid = new byte[]{0};
        assertEquals(INVALID_TEXT, assertThrows(ParsingFailure.class,
                () -> normal.parse(new ByteArrayInputStream(invalid), 1, hash(invalid), "text/plain")).code());
        assertEquals("hello", normal.parse(new ByteArrayInputStream(input), input.length, hash(input), "text/plain").text());
        assertEmpty();
    }

    @Test void concurrentCallsAreRejectedWithoutOpeningAnotherChild() throws Exception {
        var runner = new ParserProcess(root, Duration.ofSeconds(15));
        byte[] input = "a".getBytes(StandardCharsets.UTF_8);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        InputStream blocked = new ByteArrayInputStream(input) {
            @Override public int read(byte[] buffer, int start, int count) {
                entered.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return super.read(buffer, start, count);
            }
        };
        Thread first = Thread.ofPlatform().start(() -> {
            try { runner.parse(blocked, 1, hash(input), "text/plain"); } catch (Throwable e) { failure.set(e); }
        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(PARSER_BUSY, assertThrows(ParsingFailure.class,
                    () -> runner.parse(new ByteArrayInputStream(input), 1, hash(input), "text/plain")).code());
        } finally { release.countDown(); first.join(20000); }
        assertFalse(first.isAlive()); assertNull(failure.get()); assertEmpty();
    }

    @Test void supervisorKillsActualProcessAndDoesNotLeakBusinessEnvironment() throws Exception {
        ProcessSupervisor.run(command("environment"), root, Duration.ofSeconds(10));
        assertEquals("clean", Files.readString(root.resolve("environment.txt")));
        assertEquals(PARSER_TIMEOUT, assertThrows(ParsingFailure.class,
                () -> ProcessSupervisor.run(command("sleep"), root, Duration.ofSeconds(3))).code());
        long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void supervisorInterruptionTerminatesChildAndRestoresInterruptFlag() throws Exception {
        var caught = new java.util.concurrent.atomic.AtomicReference<ParsingFailure.Code>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { ProcessSupervisor.run(command("sleep"), root, Duration.ofSeconds(30)); }
            catch (IOException e) { if (e instanceof ParsingFailure p) caught.set(p.code()); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(root.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(root.resolve("child.pid")));
        thread.interrupt(); thread.join(10000);
        assertFalse(thread.isAlive()); assertTrue(interrupted.get()); assertEquals(PARSER_UNAVAILABLE, caught.get());
        long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private List<String> command(String action) {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Probe.class.getName(), root.toString(), action);
    }

    private void assertEmpty() throws IOException {
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }

    private static String hash(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            if (args[1].equals("environment")) {
                Set<String> allowed = Set.of("HOME", "TMP", "TEMP", "SystemRoot", "WINDIR");
                Files.writeString(directory.resolve("environment.txt"), System.getenv().keySet().stream().allMatch(allowed::contains) ? "clean" : "unexpected");
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(30000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }));
            Files.writeString(directory.resolve("child.pid.part"), Long.toString(ProcessHandle.current().pid()));
            Files.move(directory.resolve("child.pid.part"), directory.resolve("child.pid"), StandardCopyOption.ATOMIC_MOVE);
            Thread.sleep(30000);
        }
    }
}
