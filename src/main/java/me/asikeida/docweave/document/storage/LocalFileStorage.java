package me.asikeida.docweave.document.storage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;
import me.asikeida.docweave.document.config.DocumentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 不透明 key、禁止符号链接、同目录原子发布；不在文件卷中使用客户端文件名。 */
@Component
@EnableConfigurationProperties(DocumentProperties.class)
public class LocalFileStorage implements FileStorage {
    private final Path root;
    public LocalFileStorage(DocumentProperties properties) { root=properties.root().toAbsolutePath().normalize(); }
    private Path path(String key, String suffix) throws IOException {
        try { if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid storage identifier"); }
        // root 是受部署方控制的路径；检查每级现有目录，避免可替换的链接逃逸。
        for (Path p=root; p!=null; p=p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Symbolic storage root");
        Path p=root.resolve(key+suffix);
        if (Files.isSymbolicLink(p)) throw new IOException("Symbolic storage object");
        return p;
    }
    @Override public void write(String key, InputStream input, long size, String expected) throws IOException {
        Path temp=path(key,".part"), target=path(key,".bin");
        Files.createDirectories(root);
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        long count=0;
        try (OutputStream out=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            byte[] buffer=new byte[16384]; int n;
            while ((n=input.read(buffer))!=-1) {
                count+=n; if (count>size || Thread.currentThread().isInterrupted()) throw new IOException("Upload interrupted or size mismatch");
                digest.update(buffer,0,n); out.write(buffer,0,n);
            }
        }
        if (count!=size || !HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("File integrity mismatch");
        // 不回退为非原子移动，部署存储不支持时失败并进入持久清理。
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
    }
    @Override public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key,".bin"),LinkOption.NOFOLLOW_LINKS);
    }
    @Override public void delete(String key) throws IOException {
        Files.deleteIfExists(path(key,".part")); Files.deleteIfExists(path(key,".bin"));
    }
}
