package me.asikeida.docweave.ingestion.parser;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 按父进程实例分开暂存；只回收已退出实例的过期私有目录，不扫描原文件卷。 */
public final class ParserWorkspace {
    private final Path root;

    public ParserWorkspace(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Path current() {
        var current = ProcessHandle.current();
        long started = current.info().startInstant().orElseThrow().toEpochMilli();
        return root.resolve("instance-" + current.pid() + "-" + started);
    }

    /** 每次最多检查二十个旧实例；无法确认进程身份或子进程退出时保留目录。 */
    public int recoverRetired(Instant now) throws IOException {
        for (Path p = root; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Unsafe parser root");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        List<Path> directories;
        try (var entries = Files.list(root)) { directories = entries.filter(p -> !p.equals(current())).sorted().limit(20).toList(); }
        int removed = 0;
        for (Path directory : directories) {
            String[] owner = directory.getFileName().toString().split("-");
            if (owner.length != 3 || !owner[0].equals("instance") || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(now.minus(Duration.ofMinutes(3)))) continue;
            if (!dead(owner[1] + ":" + owner[2])) continue;
            List<Path> contents;
            try (var paths = Files.walk(directory)) { contents = paths.limit(4097).toList(); }
            if (contents.size() > 4096) continue;
            // 任何链接或未知/仍运行的 child.owner 都保留，不猜测 PID 所指进程。
            boolean unsafe = false;
            for (Path path : contents) {
                if (Files.isSymbolicLink(path)) { unsafe = true; break; }
                if (path.getFileName().toString().equals("child.owner")
                        && (Files.size(path) > 80 || !dead(Files.readString(path)))) { unsafe = true; break; }
            }
            if (unsafe) continue;
            for (Path path : contents.stream().sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            removed++;
        }
        return removed;
    }

    private static boolean dead(String marker) {
        try {
            String[] identity = marker.split(":");
            if (identity.length != 2) return false;
            long pid = Long.parseLong(identity[0]), started = Long.parseLong(identity[1]);
            if (pid <= 0 || started <= 0) return false;
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) return true;
            return process.get().info().startInstant().map(time -> time.toEpochMilli() != started).orElse(false);
        } catch (NumberFormatException e) { return false; }
    }
}
