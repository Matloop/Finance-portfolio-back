// Crie este novo arquivo: RequestLoggingFilter.java
package com.example.carteira.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

@Component
@Order(1) // Garante que este filtro execute o mais cedo possível
public class RequestLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;

        // Só loga se a requisição for para um endpoint de login/oauth2
        if (req.getRequestURI().contains("/login") || req.getRequestURI().contains("/oauth2")) {
            logger.info("================= OAUTH2 REQUEST DETECTED =================");
            logger.info("Request: {} {}", req.getMethod(), req.getRequestURI());
            logger.info("Scheme: {}", req.getScheme());
            logger.info("Remote Addr: {}", req.getRemoteAddr());

            Enumeration<String> headerNames = req.getHeaderNames();
            if (headerNames != null) {
                Collections.list(headerNames).forEach(header ->
                        logger.info("Header -> {}: {}", header, req.getHeader(header))
                );
            }
            logger.info("=========================================================");
        }

        chain.doFilter(request, response);
    }
}