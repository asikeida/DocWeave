package me.asikeida.docweave.knowledgebase.dto;

public record DocumentDetail(DocumentSummary document,String content) {
    @Override public String toString(){return "DocumentDetail[id="+document.id()+", content=OMITTED]";}
}
