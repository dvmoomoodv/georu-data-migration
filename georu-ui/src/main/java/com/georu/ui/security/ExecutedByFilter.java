package com.georu.ui.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 실행자 헤더 {@code X-Executed-By} 를 로그인한 사람 이름으로 <b>바꿔치기</b>한다. 컨트롤러 16곳은 그대로 {@code @RequestHeader} 로 받으므로
 * 손대지 않아도 실행 기록 · 게이트 · 생성 기록의 실행자가 로그인 사용자가 되고, 클라이언트가 헤더로 다른 이름을 보낼 수 없다.
 * 내부 계정(SBA)은 건드리지 않는다.
 */
final class ExecutedByFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Executed-By";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MigrationUser u && !u.internal()) {
            chain.doFilter(new Wrapped(request, u.displayName()), response);
            return;
        }
        chain.doFilter(request, response);
    }

    static final class Wrapped extends HttpServletRequestWrapper {
        private final String by;

        Wrapped(HttpServletRequest request, String by) {
            super(request);
            this.by = by;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? by : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(List.of(by)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.Set<String> names = new java.util.LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
