package com.acme.auth.account;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class Accounts implements ApplicationRunner {

    private final LoginThrottle throttle;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final String adminUsername;
    private final String adminPassword;

    Accounts(AccountRepository accounts, PasswordEncoder passwords, LoginThrottle throttle,
             @Value("${auth.admin-username}") String adminUsername,
             @Value("${auth.admin-password}") String adminPassword) {
        this.throttle = throttle;
        this.accounts = accounts;
        this.passwords = passwords;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!credentialErrors(adminUsername, adminPassword).isEmpty()) {
            throw new IllegalStateException("Administrator credentials do not meet AUTH-1 requirements");
        }
        insert(adminUsername, adminPassword, "Administrator", true);
        if (accounts.findByUsername(adminUsername).filter(Account::isAdministrator).isEmpty()) {
            throw new IllegalStateException("Administrator username is already assigned to a customer");
        }
    }

    Profile register(String username, String password, String displayName) {
        var errors = credentialErrors(username, password);
        if (displayName == null || displayName.isEmpty()
                || displayName.codePointCount(0, displayName.length()) > 50 || displayName.indexOf(0) >= 0) {
            errors.add(new AuthProblems.FieldError("displayName", "Use 1 to 50 characters"));
        }
        AuthProblems.validate(errors);
        UUID id = insert(username, password, displayName, false)
                .orElseThrow(() -> new AuthProblems.Failure(AuthProblems.Kind.USERNAME_TAKEN));
        return new Profile(id.toString(), username, displayName, List.of("USER"));
    }

    Profile authenticate(String username, String password) {
        // Use the exact same accepted username as the repository lookup, including failed password validation.
        if (username != null && username.matches("[a-z0-9_]{3,20}")) throttle.attempt(username);
        AuthProblems.validate(credentialErrors(username, password));
        var account = accounts.findByUsername(username);
        boolean matches = passwords.matches(password, account.map(Account::passwordHash).orElse(dummyHash));
        if (!matches || account.isEmpty()) {
            throw new AuthProblems.Failure(AuthProblems.Kind.INVALID_CREDENTIALS);
        }
        return account.get().profile();
    }

    Profile profile(String subject) {
        return accounts.findById(UUID.fromString(subject))
                .orElseThrow(() -> new AuthProblems.Failure(AuthProblems.Kind.NOT_FOUND))
                .profile();
    }

    private Optional<UUID> insert(String username, String password, String displayName, boolean administrator) {
        UUID random = UUID.randomUUID();
        // RFC 9562 UUIDv7 uses 48 Unix timestamp bits and the RFC variant.
        long timestampAndVersion = (System.currentTimeMillis() << 16) | 0x7000 | (random.getMostSignificantBits() & 0xfff);
        UUID id = new UUID(timestampAndVersion, random.getLeastSignificantBits());
        int inserted = accounts.insert(id, username, passwords.encode(password), displayName, administrator);
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    private static ArrayList<AuthProblems.FieldError> credentialErrors(String username, String password) {
        var errors = new ArrayList<AuthProblems.FieldError>();
        if (username == null || !username.matches("[a-z0-9_]{3,20}")) {
            errors.add(new AuthProblems.FieldError("username", "Use 3 to 20 lowercase letters, digits or underscores"));
        }
        if (password == null || password.codePointCount(0, password.length()) < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            errors.add(new AuthProblems.FieldError("password", "Use at least 8 characters and at most 72 UTF-8 bytes"));
        }
        return errors;
    }

    record Profile(String id, String username, String displayName, List<String> roles) {}
}
