package me.asikeida.docweave.knowledgebase.dto;

public record DocumentSummary(String id,String knowledgeBaseId,String title,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"}) String sampleKey,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"}) String seedVersion,String sha256,String indexState,
        @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"SAMPLE","UPLOAD"}) String source,
        @io.swagger.v3.oas.annotations.media.Schema(types={"integer","null"},format="int64") Long byteSize,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"}) String mediaType,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"},format="date-time") java.time.Instant createdAt) {
    public DocumentSummary(String id,String knowledgeBaseId,String title,String sampleKey,String seedVersion,String sha256,String indexState){
        this(id,knowledgeBaseId,title,sampleKey,seedVersion,sha256,indexState,"SAMPLE",null,null,null);
    }
}
