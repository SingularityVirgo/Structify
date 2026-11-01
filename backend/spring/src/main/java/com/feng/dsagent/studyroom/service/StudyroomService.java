package com.feng.dsagent.studyroom.service;


import com.feng.dsagent.studyroom.error.StudyroomErrorCode;
import com.feng.dsagent.studyroom.converter.StudyroomRecordConverter;
import com.feng.dsagent.studyroom.dto.StudyroomFollowUpContextDTO;
import com.feng.dsagent.studyroom.support.StudyroomResourceCatalog;
import com.feng.dsagent.studyroom.support.StudyroomSceneAdapter;
import com.feng.dsagent.studyroom.entity.StudyroomRecordsEntity;
import com.feng.dsagent.studyroom.domain.StudyroomScene;
import com.feng.dsagent.studyroom.mapper.StudyroomMapper;
import com.feng.dsagent.studyroom.vo.StudyroomVO;

import com.feng.dsagent.aiquota.support.AiQuotaExecution;
import com.feng.dsagent.common.exception.BizException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudyroomService {

    private final AiQuotaExecution execution;
    private final StudyroomMapper rooms;
    private final StudyroomResourceCatalog resourceCatalog;
    private final ObjectMapper mapper;
    private final StudyroomRecordConverter recordConverter;

    public StudyroomService(
        AiQuotaExecution execution,
        StudyroomMapper rooms,
        StudyroomResourceCatalog resourceCatalog,
        ObjectMapper mapper,
        StudyroomRecordConverter recordConverter
    ) {
        this.execution = execution;
        this.rooms = rooms;
        this.resourceCatalog = resourceCatalog;
        this.mapper = mapper;
        this.recordConverter = recordConverter;
    }

    public String currentRun(String roomId, long userId) {
        return rooms.selectCurrentRunId(roomId, userId);
    }

    public Optional<StudyroomRecordsEntity.Aggregate> findOwned(String studyroomId, long userId) {
        Map<String, Object> row = rooms.selectOwnedRoom(studyroomId, userId);
        if (row == null) return Optional.empty();
        StudyroomRecordsEntity.Room room = recordConverter.toRoom(row);
        List<StudyroomRecordsEntity.Step> steps = rooms.selectCurrentSteps(studyroomId).stream()
            .map(recordConverter::toStep).toList();
        List<StudyroomRecordsEntity.Message> messages = rooms.selectCurrentMessages(studyroomId).stream()
            .map(recordConverter::toMessage).toList();
        return Optional.of(new StudyroomRecordsEntity.Aggregate(room, steps, messages));
    }

    @Transactional
    public void appendExchange(String room, long user, List<StudyroomRecordsEntity.Message> messages, String expectedRun) {
        var locked = rooms.selectOwnedRoomForUpdate(room, user);
        if (locked == null) throw StudyroomErrorCode.STUDYROOM_NOT_FOUND.toException();
        if (expectedRun == null || !Objects.equals(locked.get("current_run_id"), expectedRun))
            throw StudyroomErrorCode.RUN_SUPERSEDED.toException();
        for (var message : messages)
            if (message.stepId() != null && rooms.countCurrentStep(room, message.stepId()) == 0)
                throw StudyroomErrorCode.STUDYROOM_CONTEXT_INVALID_CONFLICT.toException();
        rooms.bumpRoomVersion(room);
        int next = rooms.nextMessageOrder(room);
        for (var message : messages) {
            rooms.insertMessage(
                message.id(), room, next++, message.role(), message.content(), message.sourcesJson(),
                message.stepId(), timestamp(message.createdAt()));
        }
    }

    public StudyroomVO get(Long userId, String studyroomId) {
        execution.requireFormalAuthentication(userId);
        StudyroomRecordsEntity.Aggregate aggregate = findOwned(studyroomId, userId)
            .orElseThrow(StudyroomService::notFound);
        List<StudyroomResourceCatalog.Candidate> candidates = resourceCatalog.candidates(aggregate.room().chapterId());
        Map<String, StudyroomResourceCatalog.Candidate> indexed = candidates.stream().collect(java.util.stream.Collectors.toMap(
            candidate -> candidate.type().value() + ":" + candidate.id(), candidate -> candidate, (left, right) -> left
        ));
        List<StudyroomVO.Step> steps = aggregate.steps().stream().map(step -> new StudyroomVO.Step(
            step.id(), step.stepKey(), step.type(), step.title(), step.explanation(), readStrings(step.evidenceJson()),
            readResources(step.resourcesJson(), indexed), step.stepOrder(), step.supplemental(), step.parentStepId(), step.createdAt(), sceneFor(step, indexed)
        )).toList();
        List<StudyroomVO.Message> messages = aggregate.messages().stream()
            .skip(Math.max(0, aggregate.messages().size() - RECENT_MESSAGE_LIMIT))
            .map(message -> new StudyroomVO.Message(
                message.id(), message.role(), message.content(), readStrings(message.sourcesJson()), message.stepId(), message.createdAt()
            )).toList();
        StudyroomRecordsEntity.Room room = aggregate.room();
        return new StudyroomVO(room.id(), room.chapterId(), room.title(), room.initialQuestion(), room.summary(),
            room.currentStepId(), room.version(), room.createdAt(), room.updatedAt(), steps, messages);
    }

    public StudyroomVO.Progress updateProgress(Long userId, String studyroomId, String stepId) {
        execution.requireFormalAuthentication(userId);
        int updated = stepId == null
            ? rooms.clearProgress(studyroomId, userId)
            : rooms.updateProgress(stepId, studyroomId, userId);
        if (updated == 0) throw notFound();
        StudyroomRecordsEntity.Room room = recordConverter.toRoom(rooms.selectOwnedRoom(studyroomId, userId));
        return new StudyroomVO.Progress(room.currentStepId(), room.version(), room.updatedAt());
    }

    public String validateQuestionContext(long userId,String studyroomId,StudyroomFollowUpContextDTO requested) {
        execution.requireFormalAuthentication(userId);
        var aggregate = findOwned(studyroomId, userId).orElseThrow(StudyroomService::notFound);
        StudyroomFollowUpContextDTO context=requested;
        if(context==null) {
            var current=aggregate.steps().stream().filter(s->s.id().equals(aggregate.room().currentStepId())).findFirst().orElseThrow(()->StudyroomErrorCode.FRAGMENT_NOT_READY.toException("请先选择就绪的讲解"));
            var refs=resourceCatalog.candidates(aggregate.room().chapterId()).stream().collect(java.util.stream.Collectors.toMap(c->c.type().value()+":"+c.id(),c->c,(a,b)->a));
            var first=sceneFor(current,refs).scenes().getFirst();
            context=new StudyroomFollowUpContextDTO(current.id(),first.id(),null,null,null);
        }
        final var selectedContext=context;
        var step = aggregate.steps().stream().filter(s -> s.id().equals(selectedContext.stepId())).findFirst()
            .orElseThrow(() -> StudyroomErrorCode.STUDYROOM_CONTEXT_INVALID.toException());
        var indexed = resourceCatalog.candidates(aggregate.room().chapterId()).stream().collect(java.util.stream.Collectors.toMap(
            c -> c.type().value() + ":" + c.id(), c -> c, (a,b) -> a));
        var scenes = sceneFor(step, indexed);
        var scene = scenes.scenes().stream().filter(s -> s.id().equals(selectedContext.sceneId())).findFirst()
            .orElseThrow(() -> StudyroomErrorCode.STUDYROOM_CONTEXT_INVALID.toException("当前场景无效"));
        if (context.resourceId() != null && scene.items().stream().noneMatch(i -> i.resourceId().equals(selectedContext.resourceId())))
            throw StudyroomErrorCode.STUDYROOM_CONTEXT_INVALID.toException("当前资源不属于本步骤场景");
        if ((context.codeMode() != null && (!scene.kind().equals("C_CODE") || !java.util.Set.of("READ", "EXPERIMENT").contains(context.codeMode())))
            || (context.runResult() != null && (!scene.kind().equals("C_CODE") || !"EXPERIMENT".equals(context.codeMode())
                || context.resourceId() == null || (context.runResult().stdout() != null && context.runResult().stdout().length() > 4000)
                || (context.runResult().stderr() != null && context.runResult().stderr().length() > 4000))))
            throw StudyroomErrorCode.STUDYROOM_CONTEXT_INVALID.toException("代码上下文无效");
        return mapper.writeValueAsString(context);
    }

    private StudyroomScene.Orchestration sceneFor(StudyroomRecordsEntity.Step step, Map<String, StudyroomResourceCatalog.Candidate> indexed) {
        var refs = readResources(step.resourcesJson(), indexed);
        if (step.orchestrationJson() == null) throw StudyroomErrorCode.STUDYROOM_CONTENT_INVALID.toException();
        return StudyroomSceneAdapter.parse(mapper, step.orchestrationJson(), refs);
    }

    private List<String> readStrings(String json) {
        try {
            JsonNode root = mapper.readTree(json == null ? "[]" : json);
            if (!root.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (JsonNode item : root) if (item.isString()) values.add(item.asString());
            return List.copyOf(values);
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    private List<StudyroomVO.Resource> readResources(
        String json,
        Map<String, StudyroomResourceCatalog.Candidate> candidates
    ) {
        try {
            JsonNode root = mapper.readTree(json == null ? "[]" : json);
            if (!root.isArray()) return List.of();
            List<StudyroomVO.Resource> resources = new ArrayList<>();
            for (JsonNode item : root) {
                String type = item.path("type").asString("");
                String id = item.path("id").asString("");
                if (type.isBlank() || id.isBlank()) continue;
                StudyroomResourceCatalog.Candidate candidate = candidates.get(type + ":" + id);
                Object parameters = withSignedPptUrl(type, id, mapper.convertValue(item.path("parameters"), Object.class));
                resources.add(new StudyroomVO.Resource(
                    type, id, candidate == null ? "" : candidate.title(), candidate == null ? "" : candidate.description(),
                    parameters, candidate != null
                ));
            }
            return List.copyOf(resources);
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /** Overlay the current signed slide URL so already-published fragments pick up CDN-cacheable addresses. */
    private Object withSignedPptUrl(String type, String id, Object parameters) {
        if (!"ppt".equalsIgnoreCase(type)) {
            return parameters;
        }
        String url = resourceCatalog.pptImageUrl(id);
        if (url == null || url.isBlank()) {
            return parameters;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (parameters instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String name) {
                    copy.put(name, entry.getValue());
                }
            }
        }
        copy.put("imageUrl", url);
        return copy;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static BizException notFound() {
        return StudyroomErrorCode.STUDYROOM_NOT_FOUND.toException();
    }

    private static final int RECENT_MESSAGE_LIMIT = 100;
}
