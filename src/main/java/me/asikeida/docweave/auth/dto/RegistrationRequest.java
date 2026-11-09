package me.asikeida.docweave.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String username,
        @NotBlank @Size(min = 12, max = 72) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password,
        @NotBlank @Size(max = 80) String displayName) {
    @Override public String toString() { return "RegistrationRequest[credentials=REDACTED]"; }
}
