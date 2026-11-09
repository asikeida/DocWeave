package me.asikeida.docweave.ingestion.parser;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ParserLifecycleTests {
    @TempDir Path root;

    @Test void watchdogTerminatesItsJvmAtDeadline() throws Exception {
        var failure = assertThrows(ParsingFailure.class, () -> ProcessSupervisor.run(command("deadline"), root, Duration.ofSeconds(10)));
        assertEquals(ParsingFailure.Code.PARSER_TIMEOUT, failure.code());
        long pid = Long.parseLong(Files.readString(root.resolve("watched.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void childExitsWhenItsOwnerProcessDisappears() throws Exception {
        ProcessSupervisor.run(command("parent"), root, Duration.ofSeconds(10));
        long pid = Long.parseLong(Files.readString(root.resolve("watched.pid")));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void cleanupOnlyRemovesExpiredDeadOwnersAndRetainsLiveOrUnknownChildren() throws Exception {
        var workspace = new ParserWorkspace(root);
        var current = ProcessHandle.current(); long started = current.info().startInstant().orElseThrow().toEpochMilli();
        Path old = root.resolve("instance-" + current.pid() + "-" + (started - 1));
        Files.createDirectories(old.resolve("attempt-fixture")); Files.writeString(old.resolve("attempt-fixture/input.bin"), "fictional");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(300)));
        Path liveChild = root.resolve("instance-" + current.pid() + "-" + (started - 2));
        Files.createDirectories(liveChild); Files.writeString(liveChild.resolve("child.owner"), current.pid() + ":" + started);
        Files.setLastModifiedTime(liveChild, FileTime.from(Instant.now().minusSeconds(300)));
        Files.createDirectories(workspace.current()); Files.setLastModifiedTime(workspace.current(), FileTime.from(Instant.now().minusSeconds(300)));
        assertEquals(1, workspace.recoverRetired(Instant.now()));
        assertFalse(Files.exists(old)); assertTrue(Files.exists(liveChild)); assertTrue(Files.exists(workspace.current()));
    }

    private List<String> command(String mode) { return command(mode, null); }
    private List<String> command(String mode, String parent) {
        return commandFor(root, mode, parent);
    }
    private static List<String> commandFor(Path root, String mode, String parent) {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        var result = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", name).toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Probe.class.getName(), root.toString(), mode));
        if (parent != null) result.add(parent);
        return result;
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            if (args[1].equals("parent")) {
                Process child = new ProcessBuilder(commandFor(root, "orphan", Long.toString(ProcessHandle.current().pid())))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(root.resolve("watched.pid")) && System.nanoTime() < deadline) Thread.sleep(20);
                if (!Files.exists(root.resolve("watched.pid"))) { child.destroyForcibly(); throw new IllegalStateException("fixture did not start"); }
                return;
            }
            long parent = args.length > 2 ? Long.parseLong(args[2]) : ProcessHandle.current().pid();
            ParserWatchdog.start(args[1].equals("deadline") ? Duration.ofMillis(300) : Duration.ofSeconds(5),
                    () -> ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false));
            Files.writeString(root.resolve("watched.part"), Long.toString(ProcessHandle.current().pid()));
            Files.move(root.resolve("watched.part"), root.resolve("watched.pid"), StandardCopyOption.ATOMIC_MOVE);
            Thread.sleep(30000);
        }
    }
}
