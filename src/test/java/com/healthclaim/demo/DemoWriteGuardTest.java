package com.healthclaim.demo;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoWriteGuardTest {

    private final DemoWriteGuard guard = new DemoWriteGuard("operator");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private boolean call(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setDispatcherType(DispatcherType.REQUEST);
        return guard.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void publicAdminCannotChangeSharedData() {
        signIn("admin");
        for (String[] write : new String[][] {
                {"POST", "/api/v1/policies"},
                {"POST", "/api/v1/policies/0b6c2c1a-0000-0000-0000-000000000001/documents"},
                {"POST", "/api/v1/policies/0b6c2c1a-0000-0000-0000-000000000001/ingest"},
                {"POST", "/api/v1/admin/users"},
                {"POST", "/api/v1/claims"},
                {"POST", "/api/v1/claims/0b6c2c1a-0000-0000-0000-000000000001/extract"}}) {
            assertThatThrownBy(() -> call(write[0], write[1]))
                    .as(write[0] + " " + write[1])
                    .isInstanceOf(DemoReadOnlyException.class)
                    .hasMessageContaining("read-only");
        }
    }

    @Test
    void theDemoItselfStaysOpen() {
        signIn("user");
        assertThat(call("GET", "/api/v1/policies")).isTrue();
        assertThat(call("GET", "/api/v1/policies/0b6c2c1a-0000-0000-0000-000000000001/chunks")).isTrue();
        assertThat(call("POST", "/api/v1/claims/0b6c2c1a-0000-0000-0000-000000000001/analyze")).isTrue();
        assertThat(call("POST", "/api/v1/claims/0b6c2c1a-0000-0000-0000-000000000001/analyze/stream")).isTrue();
        assertThat(call("POST", "/api/v1/claims/0b6c2c1a-0000-0000-0000-000000000001/explain")).isTrue();
        assertThat(call("POST", "/api/v1/claims/0b6c2c1a-0000-0000-0000-000000000001/review")).isTrue();
    }

    @Test
    void operatorCanMaintainTheDemo() {
        signIn("operator");
        assertThat(call("POST", "/api/v1/policies/0b6c2c1a-0000-0000-0000-000000000001/ingest")).isTrue();
        assertThat(call("POST", "/api/v1/admin/users")).isTrue();
    }

    @Test
    void anonymousWritesAreBlockedToo() {
        assertThatThrownBy(() -> call("POST", "/api/v1/policies")).isInstanceOf(DemoReadOnlyException.class);
    }

    @Test
    void asyncRedispatchIsNotCheckedTwice() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/policies");
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(guard.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
