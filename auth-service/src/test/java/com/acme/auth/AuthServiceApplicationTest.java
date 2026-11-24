package com.acme.auth;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.admin-username=admin_test",
        "auth.admin-password=test-admin-password"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthServiceApplicationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("OPS-3: Readiness includes the database and liveness stays independent")
    void healthProbes() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwords;

    @Test
    @DisplayName("AUTH-1, AUTH-2, AUTH-5, API-9: Register users with salted hashes and reject duplicates")
    void registration() throws Exception {
        var saved = body(register("register_user", "reusable-test-password", "Register User")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("register_user"))
                .andExpect(jsonPath("$.displayName").value("Register User"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist()));
        assertThat(UUID.fromString(saved.get("id").asText()).version()).isEqualTo(7);

        register("register_other", "reusable-test-password", "Another User").andExpect(status().isCreated());
        String hash = passwordHash("register_user");
        assertThat(hash).startsWith("$2").isNotEqualTo(passwordHash("register_other"));
        assertThat(passwords.matches("reusable-test-password", hash)).isTrue();

        register("register_user", "different-password", "Different Name")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        assertThat(passwordHash("register_user")).isEqualTo(hash);
    }

    @Test
    @DisplayName("AUTH-1, SEC-5, API-6: Validate fields and BCrypt's UTF-8 byte boundary")
    void validation() throws Exception {
        register("UP", "short", "")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andExpect(jsonPath("$.errors[1].field").value("password"))
                .andExpect(jsonPath("$.errors[2].field").value("displayName"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        register("unicode_user", "é".repeat(37), "Unicode User")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        register("unicode_user", "é".repeat(36), "Unicode User").andExpect(status().isCreated());
        login("unicode_user", "é".repeat(36)).andExpect(status().isOk());
        register("long_name", "test-password", "a".repeat(51))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        login("bad_login", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    @DisplayName("AUTH-3, AUTH-4, SEC-1/2, API-3/9: Verify JWT with published JWKS and read the caller")
    void loginJwksAndMe() throws Exception {
        var registered = body(register("token_user", "test-token-password", "Token User")
                .andExpect(status().isCreated()));
        var login = body(login("token_user", "test-token-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800)));
        String accessToken = login.get("accessToken").asText();
        var jwt = SignedJWT.parse(accessToken);
        var jwks = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var keys = JWKSet.parse(jwks);
        var key = (RSAKey) keys.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(keys.getKeys()).hasSize(1);
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("digital-wallet-auth");
        assertThat(claims.getSubject()).isEqualTo(registered.get("id").asText());
        assertThat(claims.getStringClaim("username")).isEqualTo("token_user");
        assertThat(claims.getStringClaim("name")).isEqualTo("Token User");
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(claims.getIssueTime().toInstant().plusSeconds(1800))
                .isAfter(Instant.now());

        var me = body(mockMvc.perform(get("/api/v1/auth/me")
                        .param("userId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()));
        assertThat(me).isEqualTo(registered);
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        int signatureStart = accessToken.lastIndexOf('.') + 1;
        char replacement = accessToken.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = accessToken.substring(0, signatureStart) + replacement
                + accessToken.substring(signatureStart + 1);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("AUTH-5: Bootstrap the configured administrator with USER and ADMIN roles")
    void administrator() throws Exception {
        var login = body(login("admin_test", "test-admin-password").andExpect(status().isOk()));
        var claims = SignedJWT.parse(login.get("accessToken").asText()).getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER", "ADMIN");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.roles[1]").value("ADMIN"));
        register("admin_test", "new-test-password", "Duplicate Admin").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AUTH-6, API-6: Unknown usernames and incorrect passwords return the same public error")
    void failedLogin() throws Exception {
        register("known_user", "known-test-password", "Known User").andExpect(status().isCreated());
        var unknown = credentialFailure("missing_user");
        var incorrect = credentialFailure("known_user");
        assertThat(unknown.remove("traceId").asText()).isNotBlank();
        assertThat(incorrect.remove("traceId").asText()).isNotBlank();
        assertThat(unknown).isEqualTo(incorrect);
    }

    @Test
    @DisplayName("API-8: OpenAPI and Swagger UI are public under /docs/auth/")
    void docs() throws Exception {
        mockMvc.perform(get("/docs/auth/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/docs/auth/ui"));
        mockMvc.perform(get("/docs/auth/ui"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/docs/auth/swagger-ui/index.html"));
        mockMvc.perform(get("/docs/auth/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/docs/auth/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists());
    }

    @Test
    @DisplayName("SEC-9, AUTH-6: five attempts for known and unknown exact usernames; success also counts")
    void loginThrottle() throws Exception {
        register("limited_known", "test-password", "Limited").andExpect(status().isCreated());
        for (String name : java.util.List.of("limited_known", "limited_unknown")) {
            for (int i = 0; i < 5; i++) login(name, "wrong-password")
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            login(name, "test-password").andExpect(status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        for (int i = 0; i < 5; i++) login("limited_invalid", "short").andExpect(status().isBadRequest());
        login("limited_invalid", "short").andExpect(status().isTooManyRequests());
        register("limited_success", "test-password", "Success").andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) login("limited_success", "test-password").andExpect(status().isOk());
        login("limited_success", "test-password").andExpect(status().isTooManyRequests());
    }

    @Test void SEC5_rejectCoercedAndOversizedCredentials() throws Exception {
        for (String body : java.util.List.of(
                "{\"username\":12345,\"password\":\"test-password\"}",
                "{\"username\":\"numeric_pass\",\"password\":123456789}",
                "{\"username\":\"test_user\",\"password\":\"test-password\",\"displayName\":true}")) {
            mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        register("a".repeat(21), "test-password", "User").andExpect(status().isBadRequest());
        register("oversized_pass", "a".repeat(73), "User").andExpect(status().isBadRequest());
    }

    private ResultActions register(String username, String password, String displayName) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                        "username", username, "password", password, "displayName", displayName))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", username, "password", password))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private ObjectNode credentialFailure(String username) throws Exception {
        return (ObjectNode) body(login(username, "wrong-test-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").isNotEmpty())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")));
    }

    private String passwordHash(String username) {
        return jdbc.sql("SELECT password_hash FROM users WHERE username = :username")
                .param("username", username).query(String.class).single();
    }
}
