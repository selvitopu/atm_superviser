package io.github.selvitopu.atmsup.supervisor.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;

class LiveSessionsTest {

    private final LiveSessions sessions = new LiveSessions();

    private MockHttpSession created() {
        MockHttpSession session = new MockHttpSession();
        sessions.onCreated(new HttpSessionCreatedEvent(session));
        return session;
    }

    private MockHttpSession signedIn(String username) {
        MockHttpSession session = created();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(username, null, List.of())));
        return session;
    }

    @Test
    void endsOnlyTheSessionsOfThatUser() {
        MockHttpSession first = signedIn("opsviewer");
        MockHttpSession second = signedIn("opsviewer");
        MockHttpSession other = signedIn("supervisor1");
        MockHttpSession anonymous = created();

        assertThat(sessions.endSessionsOf("opsviewer")).isEqualTo(2);

        assertThat(first.isInvalid()).isTrue();
        assertThat(second.isInvalid()).isTrue();
        assertThat(other.isInvalid()).isFalse();
        assertThat(anonymous.isInvalid()).isFalse();
    }

    @Test
    void forgetsDestroyedSessions() {
        MockHttpSession session = signedIn("opsviewer");
        sessions.onDestroyed(new HttpSessionDestroyedEvent(session));

        assertThat(sessions.endSessionsOf("opsviewer")).isZero();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void followsTheNewIdThatSignInGivesTheSession() {
        // sign-in changes the session id against fixation; the destroyed event then carries the new id
        MockHttpSession session = signedIn("opsviewer");
        String oldId = session.getId();
        session.changeSessionId();
        sessions.onIdChanged(new HttpSessionIdChangedEvent(session, oldId));
        sessions.onDestroyed(new HttpSessionDestroyedEvent(session));

        assertThat(sessions.endSessionsOf("opsviewer")).isZero();
    }

    @Test
    void skipsASessionThatIsAlreadyInvalid() {
        MockHttpSession session = signedIn("opsviewer");
        session.invalidate(); // before its destroyed event arrives

        assertThat(sessions.endSessionsOf("opsviewer")).isZero();
    }
}
