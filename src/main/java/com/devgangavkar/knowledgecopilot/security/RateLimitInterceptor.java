package com.devgangavkar.knowledgecopilot.security;

import com.devgangavkar.knowledgecopilot.config.ApplicationProperties;
import com.devgangavkar.knowledgecopilot.exception.RateLimitExceededException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fixed-window, in-memory rate limiter. Chat calls are keyed by user (they cost Gemini quota),
 * login/register calls are keyed by client IP (brute-force protection). A single backend
 * instance is all this app runs, so in-memory state is sufficient.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ApplicationProperties applicationProperties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitInterceptor(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Streaming responses re-dispatch the same request asynchronously; count it only once.
        if (!HttpMethod.POST.matches(request.getMethod()) || request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }

        String path = request.getRequestURI();
        if (path.startsWith("/chat/")) {
            check("chat:" + resolveUser(request), applicationProperties.rateLimit().chatRequestsPerMinute());
        } else if (path.startsWith("/auth/")) {
            check("auth:" + request.getRemoteAddr(), applicationProperties.rateLimit().authRequestsPerMinute());
        }
        return true;
    }

    private void check(String key, int limit) {
        if (limit <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.values().removeIf(window -> now - window.startedAt >= WINDOW_MILLIS);
        }

        Window window = windows.compute(key, (ignored, current) ->
                current == null || now - current.startedAt >= WINDOW_MILLIS ? new Window(now) : current);

        if (window.count.incrementAndGet() > limit) {
            throw new RateLimitExceededException("Too many requests. Please wait a minute and try again.");
        }
    }

    private String resolveUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : request.getRemoteAddr();
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
