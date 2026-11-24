package com.acme.notification.observability;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** OPS-4, SEC-7: log only server route templates and trusted identity, never headers, query strings or bodies. */
@Component
public class RequestLogging extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogging.class);
    @Bean
    FilterRegistrationBean<RequestLogging> requestLoggingRegistration(RequestLogging filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getUserPrincipal() instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
        try (var ignored = MDC.putCloseable("userId", user)) {
            try {
                chain.doFilter(request, response);
            } finally {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                log.atInfo().addKeyValue("event", "http.request")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("route", route == null ? "UNMATCHED" : route.toString())
                        .addKeyValue("status", response.getStatus()).log("HTTP request completed");
            }
        }
    }
}
