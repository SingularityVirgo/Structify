package com.feng.dsagent.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.knowledge.service.KnowledgeSearchService;
import com.feng.dsagent.security.service.JwtTokenService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.model.provider=test-provider",
    "app.model.api-key=readiness-operation-test-key",
    "app.model.base-url=https://model.example.invalid/v1",
    "app.model.name=test-model",
    "app.ai-quota.daily-token-quota=2048"
})
@AutoConfigureMockMvc
class AiReadinessOperationApiIntegrationTest {

    private static final long USER_ID = 8702L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private KnowledgeSearchService knowledge;

    @BeforeEach
    void prepareUserAndEmptyEvidence() {
        jdbc.update("DELETE FROM model_configurations");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            USER_ID,
            "readiness-operation@example.com",
            "hash"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", USER_ID, "STUDENT");
        knowledge.replace(List.of());
    }

    @AfterEach
    void resetKnowledge() {
        knowledge.replace(List.of());
    }

    @Test
    void treatsCodeAnalysisAsReadyWhenTheConfiguredModelAndQuotaAreReadyWithoutChatEvidence() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("operation", "CODE_ANALYSIS")
                .param("prompt", "Explain this implementation")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelAvailable").value(true))
            .andExpect(jsonPath("$.evidenceAvailable").value(false))
            .andExpect(jsonPath("$.quotaStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.allowFormalGeneration").value(true));
    }

    private String token() {
        return tokens.issue(USER_ID, "readiness-operation@example.com", Set.of("STUDENT"));
    }
}
