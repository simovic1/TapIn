package com.tapin.notifications;

import com.tapin.auth.AuthenticatedUser;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me/notifications")
class OwnNotificationsController {
    private final NamedParameterJdbcTemplate jdbc;
    private final PushTemplateRenderer renderer;
    private final JsonMapper json;
    OwnNotificationsController(NamedParameterJdbcTemplate jdbc, PushTemplateRenderer renderer, JsonMapper json) {
        this.jdbc = jdbc;
        this.renderer = renderer;
        this.json = json;
    }
    record Item(long id, String title, String body, String url, Instant createdAt, boolean read) {}
    record Inbox(List<Item> items, long unreadCount) {}
    private static final String OWN = "recipient_user_id = :user AND channel = 'PUSH' AND NOT redact_payload_after_send";

    @GetMapping
    Inbox list(@AuthenticationPrincipal AuthenticatedUser actor) {
        var params = Map.of("user", actor.userId());
        List<Item> items = jdbc.query("SELECT * FROM email_outbox WHERE " + OWN + " ORDER BY id DESC LIMIT 50", params, (rs, n) -> {
            Map<String, Object> payload = json.readValue(rs.getString("payload"), new TypeReference<>() {});
            var rendered = json.readTree(renderer.render(EmailEventType.valueOf(rs.getString("event_type")),
                    rs.getObject("session_id", Long.class), payload));
            return new Item(rs.getLong("id"), rendered.get("title").asString(), rendered.get("body").asString(),
                    rendered.get("url").asString(), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") != null);
        });
        Long unread = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE " + OWN + " AND read_at IS NULL", params, Long.class);
        return new Inbox(items, unread == null ? 0 : unread);
    }

    @PutMapping("/{id}/read")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        jdbc.update("UPDATE email_outbox SET read_at = COALESCE(read_at, now()) WHERE " + OWN + " AND id = :id",
                Map.of("user", actor.userId(), "id", id));
    }
}
