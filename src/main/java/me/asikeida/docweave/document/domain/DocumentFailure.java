package me.asikeida.docweave.document.domain;

/** 文档用例可安全对外公开的错误；不携带文件路径或存储异常细节。 */
public class DocumentFailure extends RuntimeException {
    private final String code;
    private final int status;
    public DocumentFailure(String code, String message, int status) { super(message); this.code=code; this.status=status; }
    public String getCode() { return code; }
    public int getStatus() { return status; }
    public static DocumentFailure unavailable() { return new DocumentFailure("STORAGE_UNAVAILABLE","文件存储暂不可用，请稍后重试",503); }
}
