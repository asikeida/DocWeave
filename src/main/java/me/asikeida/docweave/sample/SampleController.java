package me.asikeida.docweave.sample;

import java.util.List;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name="个人示例预览")
@SecurityRequirement(name="bearerAuth")
public class SampleController {
    private final SampleCatalog catalog;
    private final PersonalAreaService areas;
    public SampleController(SampleCatalog catalog, PersonalAreaService areas) { this.catalog=catalog;this.areas=areas; }

    @GetMapping("/api/v1/personal-area/samples")
    @Operation(summary="列出两份虚构只读模板",description="需要本人个人区域有效；模板不是已导入文档，readyForChat始终false。")
    ResponseEntity<List<SampleCatalog.Summary>> list(@AuthenticationPrincipal Jwt jwt) {
        areas.current(jwt.getSubject());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(catalog.list());
    }

    @GetMapping("/api/v1/personal-area/samples/{sampleId}")
    @Operation(summary="预览白名单示例原文",description="以plain-text返回正文，不渲染HTML；无效ID返回404，不接受任意路径。")
    ResponseEntity<SampleCatalog.Preview> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String sampleId) {
        areas.current(jwt.getSubject());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(catalog.preview(sampleId));
    }
}
