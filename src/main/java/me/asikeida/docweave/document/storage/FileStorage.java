package me.asikeida.docweave.document.storage;

import java.io.IOException;
import java.io.InputStream;

/** 原文件边界。key 仅由服务端生成，不能作为下载授权；写入发布成功后才能确认元数据。 */
public interface FileStorage {
    void write(String key, InputStream input, long size, String sha256) throws IOException;
    InputStream open(String key) throws IOException;
    void delete(String key) throws IOException;
}
