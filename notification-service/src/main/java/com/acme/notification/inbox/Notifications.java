package com.acme.notification.inbox;

import com.acme.notification.api.NotificationProblems;
import com.acme.notification.api.NotificationProblems.Failure;
import com.acme.notification.api.NotificationProblems.Kind;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class Notifications {
    private final JdbcClient db;
    public Notifications(JdbcClient db) { this.db = db; }
    public record Notification(UUID id, String type, String text, boolean read, UUID referenceId, Instant createdAt) {}
    public record Page(List<Notification> items, String nextCursor) {}
    private record Cursor(Instant time, UUID id) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String token) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException ex) { throw NotificationProblems.invalid("cursor", "Invalid cursor"); }
        }
    }
    public Page list(UUID user, boolean unreadOnly, int limit, String token) {
        if (limit < 1 || limit > 100) throw NotificationProblems.invalid("limit", "Use a limit from 1 to 100");
        Cursor cursor = token == null ? null : Cursor.decode(token);
        var query = db.sql("select * from notifications where user_id=:user"
                + (unreadOnly ? " and not read" : "")
                + (cursor == null ? "" : " and (created_at,id)<(:time,:id)")
                + " order by created_at desc,id desc limit :limit").param("user", user).param("limit", limit + 1);
        if (cursor != null) query.param("time", Timestamp.from(cursor.time())).param("id", cursor.id());
        var rows = query.query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getString("text"), rs.getBoolean("read"), rs.getObject("reference_id", UUID.class),
                rs.getTimestamp("created_at").toInstant())).list();
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var last = items.isEmpty() ? null : items.getLast();
        return new Page(items, more ? new Cursor(last.createdAt(), last.id()).encode() : null);
    }
    public long unread(UUID user) {
        return db.sql("select count(*) from notifications where user_id=? and not read").param(user).query(Long.class).single();
    }
    public void read(UUID user, UUID id) {
        if (db.sql("update notifications set read=true where id=? and user_id=?").params(id, user).update() == 0)
            throw new Failure(Kind.NOT_FOUND);
    }
    public void readAll(UUID user) {
        db.sql("update notifications set read=true where user_id=? and not read").param(user).update();
    }
}
