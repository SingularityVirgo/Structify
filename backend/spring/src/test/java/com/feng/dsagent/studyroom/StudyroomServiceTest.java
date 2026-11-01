package com.feng.dsagent.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feng.dsagent.aiquota.support.AiQuotaExecution;
import com.feng.dsagent.common.exception.BizException;
import com.feng.dsagent.studyroom.converter.StudyroomRecordConverter;
import com.feng.dsagent.studyroom.domain.StudyroomScene;
import com.feng.dsagent.studyroom.dto.StudyroomFollowUpContextDTO;
import com.feng.dsagent.studyroom.mapper.StudyroomMapper;
import com.feng.dsagent.studyroom.service.StudyroomService;
import com.feng.dsagent.studyroom.support.StudyroomResourceCatalog;
import com.feng.dsagent.studyroom.vo.StudyroomVO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class StudyroomServiceTest {

    private static final StudyroomRecordConverter RECORD_CONVERTER =
            Mappers.getMapper(StudyroomRecordConverter.class);

    @Test
    void rejectsFollowUpContextFromAnotherStepBeforeCallingModel() {
        var mapper = mock(StudyroomMapper.class);
        when(mapper.selectOwnedRoom("room", 9L)).thenReturn(roomRow("room", 9L, null, "标题", "问题", "摘要", null, 1));
        when(mapper.selectCurrentSteps("room")).thenReturn(List.of());
        when(mapper.selectCurrentMessages("room")).thenReturn(List.of());
        var service = new StudyroomService(
                mock(AiQuotaExecution.class),
                mapper,
                new StudyroomResourceCatalog(List.of()),
                new ObjectMapper(),
                RECORD_CONVERTER);
        assertThatThrownBy(() -> service.validateQuestionContext(
                        9L, "room", new StudyroomFollowUpContextDTO("foreign", "main", null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("当前步骤");
    }

    @Test
    void readsOwnedRoomAndMarksMissingStaticResourcesUnavailable() {
        AiQuotaExecution execution = mock(AiQuotaExecution.class);
        StudyroomMapper mapper = mock(StudyroomMapper.class);
        StudyroomResourceCatalog catalog = new StudyroomResourceCatalog(List.of());
        Instant now = Instant.now();
        when(mapper.selectOwnedRoom("room-id", 9L))
                .thenReturn(roomRow("room-id", 9L, "chapter-1", "标题", "问题", "摘要", "step-1", 2, now, now));
        when(mapper.selectCurrentSteps("room-id"))
                .thenReturn(List.of(stepRow(
                        "step-1",
                        "s1",
                        "lesson",
                        "第一步",
                        "图文讲解",
                        "[]",
                        "[{\"type\":\"ppt\",\"id\":\"slide-missing\",\"parameters\":{}}]",
                        0,
                        false,
                        null,
                        now,
                        missingSlideScene())));
        when(mapper.selectCurrentMessages("room-id"))
                .thenReturn(List.of(messageRow("message-1", "assistant", "欢迎学习", "[]", "step-1", now)));
        StudyroomService service =
                new StudyroomService(execution, mapper, catalog, new ObjectMapper(), RECORD_CONVERTER);

        StudyroomVO view = service.get(9L, "room-id");

        assertThat(view.id()).isEqualTo("room-id");
        assertThat(view.currentStepId()).isEqualTo("step-1");
        assertThat(view.steps()).singleElement().satisfies(step -> {
            assertThat(step.explanation()).isEqualTo("图文讲解");
            assertThat(step.resources()).singleElement().satisfies(resource -> {
                assertThat(resource.available()).isFalse();
                assertThat(resource.type()).isEqualTo("ppt");
            });
        });
        assertThat(view.messages()).hasSize(1);
    }

    @Test
    void overlaysServerSignedPptImageUrlOnRead() {
        StudyroomResourceCatalog catalog = mock(StudyroomResourceCatalog.class);
        when(catalog.candidates("chapter-1"))
                .thenReturn(List.of(new StudyroomResourceCatalog.Candidate(
                        com.feng.dsagent.studyroom.support.StudyroomModelOutput.ResourceType.PPT,
                        "slide-1",
                        "栈",
                        "课件",
                        List.of())));
        when(catalog.pptImageUrl("slide-1"))
                .thenReturn("/api/v1/presentation/slides/slide-1/sig.webp");
        Instant now = Instant.now();
        StudyroomMapper mapper = mock(StudyroomMapper.class);
        when(mapper.selectOwnedRoom("room-id", 9L))
                .thenReturn(roomRow("room-id", 9L, "chapter-1", "标题", "问题", "摘要", "step-1", 2, now, now));
        when(mapper.selectCurrentSteps("room-id"))
                .thenReturn(List.of(stepRow(
                        "step-1",
                        "s1",
                        "lesson",
                        "第一步",
                        "图文讲解",
                        "[]",
                        "[{\"type\":\"ppt\",\"id\":\"slide-1\",\"parameters\":{\"imageUrl\":\"/stale\"}}]",
                        0,
                        false,
                        null,
                        now,
                        signedSlideScene())));
        when(mapper.selectCurrentMessages("room-id")).thenReturn(List.of());
        StudyroomVO view = new StudyroomService(
                        mock(AiQuotaExecution.class), mapper, catalog, new ObjectMapper(), RECORD_CONVERTER)
                .get(9L, "room-id");
        assertThat(view.steps()).singleElement().satisfies(step ->
                assertThat(step.resources()).singleElement().satisfies(resource -> {
                    assertThat(resource.available()).isTrue();
                    assertThat(resource.parameters())
                            .isInstanceOf(Map.class)
                            .extracting(value -> ((Map<?, ?>) value).get("imageUrl"))
                            .isEqualTo("/api/v1/presentation/slides/slide-1/sig.webp");
                }));
    }

    @Test
    void hidesRoomsOwnedByAnotherUserAsNotFound() {
        AiQuotaExecution execution = mock(AiQuotaExecution.class);
        StudyroomMapper mapper = mock(StudyroomMapper.class);
        when(mapper.selectOwnedRoom("room-id", 8L)).thenReturn(null);
        StudyroomService service = new StudyroomService(
                execution, mapper, new StudyroomResourceCatalog(List.of()), new ObjectMapper(), RECORD_CONVERTER);

        assertThatThrownBy(() -> service.get(8L, "room-id"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> {
                    assertThat(((BizException) error).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((BizException) error).code()).isEqualTo("STUDYROOM_NOT_FOUND");
                });
    }

    @Test
    void updatesProgressWithoutCallingTheModelGraph() {
        AiQuotaExecution execution = mock(AiQuotaExecution.class);
        StudyroomMapper mapper = mock(StudyroomMapper.class);
        Instant now = Instant.now();
        when(mapper.updateProgress("step-2", "room-id", 9L)).thenReturn(1);
        when(mapper.selectOwnedRoom("room-id", 9L))
                .thenReturn(roomRow("room-id", 9L, null, "标题", "问题", "摘要", "step-2", 3, now, now));
        StudyroomService service = new StudyroomService(
                execution, mapper, new StudyroomResourceCatalog(List.of()), new ObjectMapper(), RECORD_CONVERTER);

        StudyroomVO.Progress actual = service.updateProgress(9L, "room-id", "step-2");

        assertThat(actual.currentStepId()).isEqualTo("step-2");
        assertThat(actual.version()).isEqualTo(3);
        verify(execution).requireFormalAuthentication(9L);
    }

    @Test
    void doesNotRunFollowUpGraphForAnotherUsersRoom() {
        AiQuotaExecution execution = mock(AiQuotaExecution.class);
        StudyroomMapper mapper = mock(StudyroomMapper.class);
        when(mapper.selectOwnedRoom("room-id", 8L)).thenReturn(null);
        StudyroomService service = new StudyroomService(
                execution, mapper, new StudyroomResourceCatalog(List.of()), new ObjectMapper(), RECORD_CONVERTER);

        assertThatThrownBy(() -> service.validateQuestionContext(8L, "room-id", null))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).code()).isEqualTo("STUDYROOM_NOT_FOUND"));
    }

    private String missingSlideScene() {
        return slideScene("slide-missing");
    }

    private String signedSlideScene() {
        return slideScene("slide-1");
    }

    private String slideScene(String slideId) {
        return new ObjectMapper()
                .writeValueAsString(new StudyroomScene.Orchestration(
                        2,
                        "图文讲解",
                        "CONCEPT",
                        new StudyroomScene(
                                "main",
                                "PPT",
                                "相关课件",
                                List.of(new StudyroomScene.Item(slideId, "说明", null, null)),
                                null,
                                null,
                                null,
                                null),
                        List.of()));
    }

    private static Map<String, Object> roomRow(
            String id,
            long userId,
            String chapterId,
            String title,
            String question,
            String summary,
            String currentStepId,
            int version) {
        return roomRow(id, userId, chapterId, title, question, summary, currentStepId, version, Instant.now(), Instant.now());
    }

    private static Map<String, Object> roomRow(
            String id,
            long userId,
            String chapterId,
            String title,
            String question,
            String summary,
            String currentStepId,
            int version,
            Instant createdAt,
            Instant updatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("user_id", userId);
        row.put("chapter_id", chapterId);
        row.put("title", title);
        row.put("initial_question", question);
        row.put("summary", summary);
        row.put("current_step_id", currentStepId);
        row.put("version", version);
        row.put("created_at", Timestamp.from(createdAt));
        row.put("updated_at", Timestamp.from(updatedAt));
        return row;
    }

    private static Map<String, Object> stepRow(
            String id,
            String stepKey,
            String type,
            String title,
            String explanation,
            String evidenceJson,
            String resourcesJson,
            int stepOrder,
            boolean supplemental,
            String parentStepId,
            Instant createdAt,
            String orchestrationJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("step_key", stepKey);
        row.put("step_type", type);
        row.put("title", title);
        row.put("explanation", explanation);
        row.put("evidence_json", evidenceJson);
        row.put("resources_json", resourcesJson);
        row.put("step_order", stepOrder);
        row.put("supplemental", supplemental);
        row.put("parent_step_id", parentStepId);
        row.put("created_at", Timestamp.from(createdAt));
        row.put("orchestration_json", orchestrationJson);
        return row;
    }

    private static Map<String, Object> messageRow(
            String id, String role, String content, String sourcesJson, String stepId, Instant createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("role", role);
        row.put("content", content);
        row.put("sources_json", sourcesJson);
        row.put("step_id", stepId);
        row.put("created_at", Timestamp.from(createdAt));
        return row;
    }
}