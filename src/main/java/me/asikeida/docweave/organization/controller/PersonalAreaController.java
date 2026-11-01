package me.asikeida.docweave.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.auth.service.RegistrationService;
import me.asikeida.docweave.organization.dto.PersonalArea;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "个人首次体验")
public class PersonalAreaController {
    private final RegistrationService registration;
    private final PersonalAreaService areas;
    public PersonalAreaController(RegistrationService registration, PersonalAreaService areas) { this.registration=registration; this.areas=areas; }

    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "注册并原子创建个人区域", description = "默认关闭；仅显式启用的local/test可用。成功后需要登录，示例待办为PENDING，后台入库后为IMPORTED_NOT_INDEXED。")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true)
    ResponseEntity<PersonalArea> register(@Valid @RequestBody RegistrationRequest request, HttpServletRequest servlet) {
        registration.checkAdmission(servlet.getRemoteAddr());
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store").body(registration.register(request));
    }

    @GetMapping("/api/v1/personal-area")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "只读查询本人个人区域")
    ResponseEntity<PersonalArea> current(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(areas.current(jwt.getSubject()));
    }

    @PostMapping("/api/v1/personal-area/initialize")
    @SecurityRequirement(name = "bearerAuth")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true)
    @Operation(summary = "幂等补建本人个人区域", description = "仅为历史账号补建，不恢复已删除示例，不重新激活已停用成员。")
    ResponseEntity<PersonalArea> initialize(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(areas.initialize(jwt.getSubject()));
    }
}
