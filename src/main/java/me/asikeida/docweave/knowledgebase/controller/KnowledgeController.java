package me.asikeida.docweave.knowledgebase.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;import io.swagger.v3.oas.annotations.Parameter;import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;
import me.asikeida.docweave.knowledgebase.dto.*;import me.asikeida.docweave.knowledgebase.service.KnowledgeService;

@RestController
@Tag(name="个人知识库")
@SecurityRequirement(name="bearerAuth")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service){this.service=service;}
    private <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
    @PostMapping("/api/v1/personal-area/sample-import")
    @Operation(summary="幂等初始化本人示例库和两份独立文档",description="不恢复已删除资源，不解析或索引；成功也不能问答。")
    @Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true)
    ResponseEntity<List<KnowledgeBase>> initialize(@AuthenticationPrincipal Jwt jwt){return response(service.initialize(jwt.getSubject()));}
    @GetMapping("/api/v1/knowledge-bases") @Operation(summary="列出本人可访问知识库")
    ResponseEntity<List<KnowledgeBase>> list(@AuthenticationPrincipal Jwt jwt){return response(service.list(jwt.getSubject()));}
    @GetMapping("/api/v1/knowledge-bases/{id}") @Operation(summary="查询授权知识库详情")
    ResponseEntity<KnowledgeBase> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return response(service.knowledgeBase(jwt.getSubject(),id));}
    @GetMapping("/api/v1/knowledge-bases/{id}/documents") @Operation(summary="列出授权库内文档")
    ResponseEntity<List<DocumentSummary>> documents(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return response(service.documents(jwt.getSubject(),id));}
    @GetMapping("/api/v1/documents/{id}") @Operation(summary="读取本人已入库的文档正文和版本元数据",description="示例或已解析成功的上传文档返回200；上传正文尚未生成时409 DOCUMENT_NOT_PARSED。正文仅供纯文本展示，解析成功仍为NOT_INDEXED。当前A2后台任务分支待审核，生产环境仍为A1。")
    ResponseEntity<DocumentDetail> document(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return response(service.document(jwt.getSubject(),id));}
}
