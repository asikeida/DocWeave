package me.asikeida.docweave.ingestion.parser;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** 父进程被强杀后仍有本地截止时间；只终止当前解析 JVM，不操作其他进程。 */
final class ParserWatchdog {
    static void start(Duration maximum, BooleanSupplier parentAlive) {
        long deadline = System.nanoTime() + maximum.toNanos();
        Thread.ofPlatform().daemon(true).name("parser-watchdog").start(() -> {
            while (true) {
                if (System.nanoTime() >= deadline || !parentAlive.getAsBoolean()) Runtime.getRuntime().halt(124);
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Runtime.getRuntime().halt(124); }
            }
        });
    }
}
