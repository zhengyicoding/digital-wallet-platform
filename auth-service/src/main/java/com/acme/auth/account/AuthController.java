package com.acme.auth.account;

import java.util.Map;

import com.acme.auth.token.Tokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

    private final Accounts accounts;
    private final Tokens tokens;

    AuthController(Accounts accounts, Tokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    Accounts.Profile register(@RequestBody Registration request) {
        return accounts.register(request.username(), request.password(), request.displayName());
    }

    @PostMapping("/api/v1/auth/login")
    AccessToken login(@RequestBody Credentials request) {
        var profile = accounts.authenticate(request.username(), request.password());
        return new AccessToken(tokens.issue(profile.id(), profile.username(), profile.displayName(), profile.roles()),
                "Bearer", Tokens.LIFETIME_SECONDS);
    }

    @GetMapping("/api/v1/auth/me")
    Accounts.Profile me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.profile(jwt.getSubject());
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return tokens.jwks();
    }

    record Registration(String username, String password, String displayName) {
        @Override
        public String toString() {
            return "Registration";
        }
    }

    record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials";
        }
    }

    record AccessToken(String accessToken, String tokenType, int expiresIn) {
        @Override
        public String toString() {
            return "AccessToken";
        }
    }
}
