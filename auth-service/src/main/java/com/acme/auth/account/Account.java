package com.acme.auth.account;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class Account {

    @Id
    private UUID id;
    private String username;
    private String displayName;
    private String passwordHash;
    private boolean administrator;

    protected Account() {}

    String passwordHash() {
        return passwordHash;
    }

    boolean isAdministrator() {
        return administrator;
    }

    Accounts.Profile profile() {
        return new Accounts.Profile(id.toString(), username, displayName,
                administrator ? List.of("USER", "ADMIN") : List.of("USER"));
    }
}
