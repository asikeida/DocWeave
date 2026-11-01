package me.asikeida.docweave.organization.dto;

/** 个人区域的最小身份和准备状态；WAITING_FEATURE 不表示示例已导入或可以问答。 */
public record PersonalArea(String organizationId, String workspaceId, String name, String sampleState) { }
