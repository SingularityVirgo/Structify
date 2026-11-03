package com.feng.dsagent.auth;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.feng.dsagent.auth.support.VerificationCodeManager;
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VerificationCodeManager verificationCodes;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearAuthData() {
        jdbc.update("DELETE FROM mail_configurations");
        jdbc.update("DELETE FROM verification_codes");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void registersAndReadsCurrentUserFromSessionCookie() throws Exception {
        String email = "student-api@example.com";
        String code = requestDevelopmentCode(email);

        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"STUDENT-API@example.com","code":"%s","password":"correct-horse"}
                    """.formatted(code)))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("ds_session"))
            .andExpect(cookie().httpOnly("ds_session", true))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.roles", containsInAnyOrder("STUDENT")))
            .andReturn();

        Cookie sessionCookie = registration.getResponse().getCookie("ds_session");
        mockMvc.perform(get("/api/v1/users/me").cookie(sessionCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("STUDENT")));
    }

    @Test
    void loginReturnsSessionCookieAndDoesNotRevealWhetherAccountExists() throws Exception {
        String email = "login-api@example.com";
        register(email, "correct-horse");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"login-api@example.com","password":"correct-horse"}
                    """))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("ds_session"))
            .andExpect(jsonPath("$.user.email").value(email));

        assertInvalidCredentials(email, "wrong-password");
        assertInvalidCredentials("missing-api@example.com", "wrong-password");
    }

    @Test
    void registersAndLogsInWithAUsernameWithoutBreakingTheLegacyEmailField() throws Exception {
        String email = "username-api@example.com";
        String code = requestDevelopmentCode(email);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"ACha_","code":"%s","password":"correct-horse"}
                    """.formatted(email, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.username").value("ACha_"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"ACha_","password":"correct-horse"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.username").value("ACha_"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"acha_","password":"correct-horse"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void rejectsUsernameConflicts() throws Exception {
        String firstEmail = "username-conflict-first@example.com";
        String firstCode = requestDevelopmentCode(firstEmail);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"ACha_","code":"%s","password":"correct-horse"}
                    """.formatted(firstEmail, firstCode)))
            .andExpect(status().isOk());

        String secondEmail = "username-conflict-second@example.com";
        String secondCode = requestDevelopmentCode(secondEmail);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"acha_","code":"%s","password":"correct-horse"}
                    """.formatted(secondEmail, secondCode)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUTH_USERNAME_REGISTERED"));
    }

    @Test
    void currentUserRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("X-Request-Id", "auth-api-test"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("X-Request-Id", "auth-api-test"))
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
            .andExpect(jsonPath("$.message").value("请先登录"))
            .andExpect(jsonPath("$.requestId").value("auth-api-test"))
            .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void requestCodeNeverReturnsTheVerificationCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"response-code@example.com","purpose":"register"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.developmentCode").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("developmentCode"))));
    }

    @Test
    void malformedJsonReturnsAClientErrorInsteadOfAnInternalError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
            .andExpect(jsonPath("$.message").value("请求内容不是有效的 JSON"));
    }

    @Test
    void registerAndResetPasswordRejectMissingOrBlankCodeAndPassword() throws Exception {
        assertValidationFailure(
            "/api/v1/auth/register",
            "{\"email\":\"validation-api@example.com\",\"password\":\"correct-horse\"}",
            "code"
        );
        assertValidationFailure(
            "/api/v1/auth/register",
            "{\"email\":\"validation-api@example.com\",\"code\":\"   \",\"password\":\"correct-horse\"}",
            "code"
        );
        assertValidationFailure(
            "/api/v1/auth/register",
            "{\"email\":\"validation-api@example.com\",\"code\":\"123456\"}",
            "password"
        );
        assertValidationFailure(
            "/api/v1/auth/register",
            "{\"email\":\"validation-api@example.com\",\"code\":\"123456\",\"password\":\"   \"}",
            "password"
        );

        assertValidationFailure(
            "/api/v1/auth/reset-password",
            "{\"email\":\"validation-api@example.com\",\"password\":\"correct-horse\"}",
            "code"
        );
        assertValidationFailure(
            "/api/v1/auth/reset-password",
            "{\"email\":\"validation-api@example.com\",\"code\":\"   \",\"password\":\"correct-horse\"}",
            "code"
        );
        assertValidationFailure(
            "/api/v1/auth/reset-password",
            "{\"email\":\"validation-api@example.com\",\"code\":\"123456\"}",
            "password"
        );
        assertValidationFailure(
            "/api/v1/auth/reset-password",
            "{\"email\":\"validation-api@example.com\",\"code\":\"123456\",\"password\":\"   \"}",
            "password"
        );
    }

    @Test
    void failedRegistrationDoesNotConsumeTheVerificationCode() throws Exception {
        String email = "duplicate-api@example.com";
        String code = requestDevelopmentCode(email);
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", email, "existing-hash");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","code":"%s","password":"correct-horse"}
                    """.formatted(email, code)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUTH_EMAIL_REGISTERED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT consumed_at FROM verification_codes WHERE email = ? ORDER BY id DESC LIMIT 1",
            java.sql.Timestamp.class,
            email
        )).isNull();
    }

    @Test
    void failedPasswordResetDoesNotConsumeTheVerificationCode() throws Exception {
        String email = "missing-reset-api@example.com";
        String code = verificationCodes.request(email, "reset").developmentCode();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","code":"%s","password":"correct-horse"}
                    """.formatted(email, code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AUTH_RESET_INVALID"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT consumed_at FROM verification_codes WHERE email = ? ORDER BY id DESC LIMIT 1",
            java.sql.Timestamp.class,
            email
        )).isNull();
    }

    @Test
    void wrongVerificationCodeAttemptsRemainRecordedAfterRegistrationRollsBack() throws Exception {
        String email = "attempt-limit-api@example.com";
        String code = requestDevelopmentCode(email);
        String wrongCode = (code.startsWith("0") ? "1" : "0") + code.substring(1);

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"%s","code":"%s","password":"correct-horse"}
                        """.formatted(email, wrongCode)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID"));
        }

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT attempts FROM verification_codes WHERE email = ? ORDER BY id DESC LIMIT 1",
            Integer.class,
            email
        )).isEqualTo(5);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","code":"%s","password":"correct-horse"}
                    """.formatted(email, code)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID"));
    }

    private void register(String email, String password) throws Exception {
        String code = requestDevelopmentCode(email);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","code":"%s","password":"%s"}
                    """.formatted(email, code, password)))
            .andExpect(status().isOk());
    }

    private String requestDevelopmentCode(String email) throws Exception {
        // The test profile uses a no-mail sender. The manager keeps the generated
        // code only in memory, which lets the integration test exercise the real
        // registration seam without putting the secret in an HTTP response.
        return verificationCodes.request(email, "register").developmentCode();
    }

    private void assertInvalidCredentials(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    private void assertValidationFailure(String endpoint, String body, String field) throws Exception {
        mockMvc.perform(post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.requestId").isString())
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details", hasItem(field + ": must not be blank")));
    }
}
