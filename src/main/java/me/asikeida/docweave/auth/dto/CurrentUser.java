package me.asikeida.docweave.auth.dto;

import me.asikeida.docweave.user.domain.UserAccount;

public record CurrentUser(String id, String username, String displayName) {
    public static CurrentUser from(UserAccount account) {
        return new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName());
    }
}
