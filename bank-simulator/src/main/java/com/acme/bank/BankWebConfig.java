package com.acme.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.servlet.config.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
class BankWebConfig extends OncePerRequestFilter implements WebMvcConfigurer {
    private final BankEngine bank;
    private final ObjectMapper json;
    private final byte[] apiKey;
    BankWebConfig(BankEngine bank,ObjectMapper json,@Value("${bank.api-key}") String apiKey) {
        this.bank=bank; this.json=json; this.apiKey=apiKey.getBytes(StandardCharsets.UTF_8);
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/docs/bank/","/docs/bank/ui");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/bank/v1/")) {
            var config=bank.config();
            if(config.down()) { reject(response,503,"BANK_UNAVAILABLE"); return; }
            String key=request.getHeader("X-Api-Key");
            var delayed=new ContentCachingResponseWrapper(response);
            if(key==null || !MessageDigest.isEqual(apiKey,key.getBytes(StandardCharsets.UTF_8))) {
                reject(delayed,401,"UNAUTHENTICATED");
            } else {
                chain.doFilter(request,delayed);
            }
            try { BankEngine.pause(config.responseDelayMs()); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ServletException("Interrupted",e); }
            delayed.copyBodyToResponse();
            return;
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(),BankProblems.problem(status,code));
    }
}
