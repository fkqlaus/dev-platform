package com.choi.devplatform.web;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local-only MVP: also reject DNS rebinding and cross-site browser mutations. */
@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String host = request.getServerName();
        if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            response.sendError(403);
            return;
        }
        if (!("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod()))) {
            String origin = request.getHeader("Origin");
            String expected = request.getScheme() + "://" + request.getHeader("Host");
            if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    || (origin != null && !origin.equals(expected))) {
                response.sendError(403);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
