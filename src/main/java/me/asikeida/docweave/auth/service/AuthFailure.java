package me.asikeida.docweave.auth.service;

/** 预期的认证失败，仅包含可公开的错误语义。 */
public class AuthFailure extends RuntimeException {
    private final String code;
    private final int status;

    public AuthFailure(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static AuthFailure invalidCredentials() {
        return new AuthFailure("INVALID_CREDENTIALS", "账号或密码不正确", 401);
    }

    public static AuthFailure invalidLogin() {
        return new AuthFailure("UNAUTHENTICATED", "登录已失效，请重新登录", 401);
    }

    public String getCode() { return code; }
    public int getStatus() { return status; }
}
