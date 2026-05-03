package com.merakilabs.taskq.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags each HTTP request with a {@code request_id} that is propagated into the SLF4J MDC
 * and echoed back via the {@code X-Request-Id} response header. If the caller already sent
 * an {@code X-Request-Id}, we honour it (correlate with their logs) instead of minting a new one.
 *
 * <p>JSON log records (see {@code logback-spring.xml}) include this field automatically so a
 * single grep in Loki/ELK ties together the API event, the worker event for the same job, and
 * the error stack on the same logical request.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws ServletException, IOException {
        final String inbound = request.getHeader(HEADER);
        final String requestId = inbound == null || inbound.isBlank()
                ? UUID.randomUUID().toString()
                : inbound;
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
