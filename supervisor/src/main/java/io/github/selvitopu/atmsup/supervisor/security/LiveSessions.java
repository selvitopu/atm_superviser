package io.github.selvitopu.atmsup.supervisor.security;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.stereotype.Component;

/**
 * The sessions this supervisor holds, so that an administrator's change ends a user's sessions at once instead of at
 * their next request. Ending a session publishes {@link HttpSessionDestroyedEvent}, which also closes its live stream.
 */
@Component
public class LiveSessions {

    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

    @EventListener
    void onCreated(HttpSessionCreatedEvent event) {
        sessions.put(event.getSession().getId(), event.getSession());
    }

    /** Sign-in changes the session id against fixation; the session keeps its place under the new id. */
    @EventListener
    void onIdChanged(HttpSessionIdChangedEvent event) {
        HttpSession session = sessions.remove(event.getOldSessionId());
        if (session != null) {
            sessions.put(event.getNewSessionId(), session);
        }
    }

    @EventListener
    void onDestroyed(HttpSessionDestroyedEvent event) {
        sessions.remove(event.getId());
    }

    /** Invalidates every live session signed in as {@code username} and returns how many there were. */
    public int endSessionsOf(String username) {
        int ended = 0;
        for (Map.Entry<String, HttpSession> entry : sessions.entrySet()) {
            HttpSession session = entry.getValue();
            try {
                if (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context
                        && context.getAuthentication() != null
                        && username.equals(context.getAuthentication().getName())) {
                    session.invalidate();
                    ended++;
                }
            } catch (IllegalStateException alreadyInvalid) {
                sessions.remove(entry.getKey(), session);
            }
        }
        return ended;
    }
}
