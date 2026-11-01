package me.asikeida.docweave.knowledgebase.dto;

public record DocumentSummary(String id,String knowledgeBaseId,String title,String sampleKey,String seedVersion,String sha256,String indexState) { }
