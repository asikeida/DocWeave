package me.asikeida.docweave.document.controller;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.nio.charset.StandardCharsets;
import me.asikeida.docweave.document.service.DocumentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name="文档文件")
@SecurityRequirement(name="bearerAuth")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service){this.service=service;}
    @PostMapping(value="/api/v1/knowledge-bases/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="上传本人知识库的小文件",description="PDF/TXT/Markdown，默认最大20MiB；原文件保存不等于已解析。同库重复复用已有文档，不是秒传。安全重放为200，首次完成为201。")
    @Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true)
    @ApiResponse(responseCode="201",description="新文档已保存",content=@Content(schema=@Schema(implementation=DocumentService.Result.class)))
    @ApiResponse(responseCode="200",description="重复内容或幂等重放",content=@Content(schema=@Schema(implementation=DocumentService.Result.class)))
    ResponseEntity<DocumentService.Result> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@RequestPart("file") MultipartFile file){
        var result=service.upload(jwt.getSubject(),id,key,file);
        return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/v1/documents/{id}/download")
    @Operation(summary="鉴权下载本人可访问的原文件",description="响应为二进制附件，示例无原文件时404；不返回磁盘路径，不提供公开直链。")
    @ApiResponse(responseCode="200",description="二进制原文件",content=@Content(mediaType="application/octet-stream",schema=@Schema(type="string",format="binary")))
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        var file=service.download(jwt.getSubject(),id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentLength(file.size())
                .contentType(MediaType.parseMediaType(file.mime())).header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(file.input()));
    }
    @DeleteMapping("/api/v1/documents/{id}")
    @Operation(summary="删除本人可访问的文档",description="立即撤销逻辑可见性，原文件由持久清理任务回收；本人已删除资源重复请求204，其他未知资源404。")
    @Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true)
    @ApiResponse(responseCode="204",description="文档不可访问，物理清理异步完成")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        service.delete(jwt.getSubject(),id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
