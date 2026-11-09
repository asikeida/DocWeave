package me.asikeida.docweave.ingestion.parser;

import java.io.IOException;

/** 跨进程只传递允许的稳定错误码，不传播文件路径、内容或解析库错误。 */
public final class ParsingFailure extends IOException {
    public enum Code {
        INVALID_FILE, INVALID_TEXT, ENCRYPTED_PDF, PDF_PAGE_LIMIT, NO_EXTRACTABLE_TEXT,
        TEXT_TOO_LARGE, UNSUPPORTED_FILE_TYPE, PARSER_TIMEOUT, PARSER_PROCESS_FAILED,
        PARSER_BUSY, PARSER_UNAVAILABLE, PARSER_TERMINATION_FAILED, INVALID_PARSER_RESULT
    }

    private final Code code;

    public ParsingFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
