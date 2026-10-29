package me.asikeida.docweave.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String username,
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password
) {
    @Override
    public String toString() { return "LoginRequest[credentials=REDACTED]"; }
}
