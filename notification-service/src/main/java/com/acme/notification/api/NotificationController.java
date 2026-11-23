package com.acme.notification.api;

import com.acme.notification.inbox.Notifications;
import com.acme.notification.inbox.NotificationCounters;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final Notifications notifications;
    private final NotificationCounters counters;
    public NotificationController(Notifications notifications, NotificationCounters counters) {
        this.notifications = notifications; this.counters = counters;
    }
    @GetMapping
    public Notifications.Page list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return notifications.list(UUID.fromString(jwt.getSubject()), unreadOnly, limit, cursor);
    }
    public record Count(long count) {}
    @GetMapping("/unread-count")
    public Count count(@AuthenticationPrincipal Jwt jwt) { return new Count(notifications.unread(UUID.fromString(jwt.getSubject()))); }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { notifications.read(UUID.fromString(jwt.getSubject()), id); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal Jwt jwt) { notifications.readAll(UUID.fromString(jwt.getSubject())); }
    @GetMapping("/admin/stats")
    public NotificationCounters.Stats stats() { return counters.stats(); }
}
