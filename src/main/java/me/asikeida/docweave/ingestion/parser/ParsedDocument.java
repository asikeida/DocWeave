package me.asikeida.docweave.ingestion.parser;

import java.util.List;

/** 规范化正文和 UTF-16 左闭右开跨度；PDF 页码及文本行号从 1 开始。不是检索切片。 */
public record ParsedDocument(String text, List<Location> locations) {
    public ParsedDocument {
        locations = List.copyOf(locations);
    }

    @Override
    public String toString() {
        return "ParsedDocument[characters=" + text.length() + ", locations=" + locations.size() + "]";
    }

    public record Location(int sequence, Integer page, Integer startLine, Integer endLine,
                           int start, int end) { }
}
