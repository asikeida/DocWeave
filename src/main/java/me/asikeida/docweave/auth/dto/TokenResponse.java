package me.asikeida.docweave.auth.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, CurrentUser user) {
    @Override
    public String toString() { return "TokenResponse[credentials=REDACTED]"; }
}
