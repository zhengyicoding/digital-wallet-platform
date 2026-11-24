package com.acme.wallet.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;

class RequestLoggingTest {
    @Test void ops4_sec7_trustedIdentityTemplatesAndMdcCleanupEvenOnFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogging.class);
        List<ILoggingEvent> entries = new ArrayList<>();
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                entries.add(event);
            }
        };
        appender.start(); logger.addAppender(appender);
        try {
            String user = UUID.randomUUID().toString();
            var request = new MockHttpServletRequest("POST", "/api/v1/deposits/private-id");
            request.setUserPrincipal(new JwtAuthenticationToken(Jwt.withTokenValue("secret-token")
                    .header("alg", "RS256").subject(user).build()));
            request.addHeader("Authorization", "Bearer secret-token");
            request.addHeader("X-Bank-Signature", "secret-signature");
            request.addHeader("X-Api-Key", "secret-api-key");
            request.setQueryString("password=secret-password");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/deposits/{id}");
            assertThatThrownBy(() -> new RequestLogging().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertThat(MDC.get("userId")).isEqualTo(user);
                throw new ServletException("handler failed");
            })).isInstanceOf(ServletException.class);
            assertThat(MDC.get("userId")).isNull();
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().getMDCPropertyMap()).containsEntry("userId", user);
            String logged = entries.getFirst().getFormattedMessage() + entries.getFirst().getKeyValuePairs();
            assertThat(logged).contains("/api/v1/deposits/{id}")
                    .doesNotContain("secret-token", "secret-signature", "secret-api-key", "secret-password", "private-id");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
