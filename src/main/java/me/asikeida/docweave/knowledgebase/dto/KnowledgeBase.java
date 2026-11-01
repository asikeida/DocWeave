package me.asikeida.docweave.knowledgebase.dto;

/** 本轮仅有继承默认空间权限的个人示例库；没有索引或模型能力。 */
public record KnowledgeBase(String id,String name,String workspaceId,int documentCount,String state) { }
