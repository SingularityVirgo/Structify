package com.feng.dsagent.studyroom.support;


import org.springframework.stereotype.Service;
import com.feng.dsagent.animation.support.DsvpLocalEngine;
import com.feng.dsagent.classroom.support.TextbookLessonIds;
import com.feng.dsagent.compiler.domain.ClassroomCodeLesson;
import com.feng.dsagent.compiler.domain.ClassroomCodeSample;
import com.feng.dsagent.compiler.support.ClassroomCodeStudyroomCatalog;
import com.feng.dsagent.presentation.support.PresentationCatalog;
import com.feng.dsagent.presentation.domain.PresentationSlide;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import com.feng.dsagent.studyroom.mapper.StudyroomMapper;

@Service
public class StudyroomResourceCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(StudyroomResourceCatalog.class);

    private final PresentationCatalog presentations;
    private final DsvpLocalEngine animationEngine;
    private final ClassroomCodeStudyroomCatalog codeCatalog;
    private final StudyroomMapper studyroom;
    private final List<Candidate> fixedCandidates;

    @Autowired
    public StudyroomResourceCatalog(
        PresentationCatalog presentations,
        DsvpLocalEngine animationEngine,
        ClassroomCodeStudyroomCatalog codeCatalog,
        StudyroomMapper studyroom
    ) {
        this.presentations = presentations;
        this.animationEngine = animationEngine;
        this.codeCatalog = codeCatalog;
        this.studyroom = studyroom;
        this.fixedCandidates = null;
    }

    public StudyroomResourceCatalog(List<Candidate> candidates) {
        this.presentations = null;
        this.animationEngine = null;
        this.codeCatalog = null;
        this.studyroom = null;
        this.fixedCandidates = List.copyOf(candidates);
    }

    public List<Candidate> candidates(String chapterId) {
        if (fixedCandidates != null) {
            return fixedCandidates;
        }
        List<Candidate> candidates = new ArrayList<>();
        addSafely(candidates, "PPT", () -> pptCandidates(chapterId));
        addSafely(candidates, "animation", () -> animationCandidates(chapterId));
        addSafely(candidates, "C code", () -> codeCandidates(chapterId));
        return List.copyOf(candidates);
    }

    public void validate(StudyroomModelOutput output, String chapterId) {
        Objects.requireNonNull(output, "output");
        validate(output, candidates(chapterId));
    }

    public void validate(StudyroomModelOutput output, List<Candidate> authorizedCandidates) {
        Objects.requireNonNull(output, "output");
        validate(output.steps(), authorizedCandidates);
    }

    public void validate(List<StudyroomModelOutput.Step> steps) {
        validate(steps, fixedCandidates == null ? List.of() : fixedCandidates);
    }

    private void validate(List<StudyroomModelOutput.Step> steps, List<Candidate> available) {
        Map<String, Candidate> indexed = new LinkedHashMap<>();
        for (Candidate candidate : available) {
            String key = key(candidate.type(), candidate.id());
            if (indexed.putIfAbsent(key, candidate) != null) {
                throw new IllegalStateException("Duplicate resource candidate: " + key);
            }
        }
        for (StudyroomModelOutput.Step step : steps) {
            for (StudyroomModelOutput.ResourceRef resource : step.resources()) {
                Candidate candidate = indexed.get(key(resource.type(), resource.id()));
                if (candidate == null) {
                    throw new IllegalArgumentException("Resource is not in the authorized candidate list: " + key(resource.type(), resource.id()));
                }
                if (step.orchestration() != null && resource.type() == StudyroomModelOutput.ResourceType.C_CODE && codeCatalog != null) {
                    var sample = codeCatalog.lessons().stream().flatMap(l -> l.samples().stream()).filter(c -> c.id().equals(resource.id())).findFirst().orElseThrow();
                    int lines = sample.code().split("\\R", -1).length;
                    for (var scene : step.orchestration().scenes()) for (var item : scene.items()) {
                        if (scene.kind().equals("C_CODE") && item.resourceId().equals(resource.id()) && item.endLine() != null && item.endLine() > lines)
                            throw new IllegalArgumentException("Code line outside sample");
                    }
                }
                if (!(resource.parameters() instanceof Map<?, ?> parameters)) {
                    throw new IllegalArgumentException("Resource parameters must be an object");
                }
                for (Object parameter : parameters.keySet()) {
                    if (!(parameter instanceof String name) || !candidate.allowedParameterKeys().contains(name)) {
                        throw new IllegalArgumentException("Resource contains an unsupported parameter");
                    }
                }
                if (resource.type() == StudyroomModelOutput.ResourceType.ANIMATION && resource.id().startsWith("tree.")
                    && parameters.containsKey("initialData")) {
                    Object data = parameters.get("initialData");
                    if (!(data instanceof List<?> nodes) || nodes.isEmpty() || nodes.getFirst() == null
                        || nodes.stream().anyMatch(node -> node != null && !(node instanceof String) && !(node instanceof Number))) {
                        throw new IllegalArgumentException("Tree animation initialData must be a non-empty level-order JSON array of node values, with null for missing children");
                    }
                }
            }
        }
    }

    private List<Candidate> pptCandidates(String chapterId) {
        Collection<PresentationSlide> slides;
        if (chapterId == null || chapterId.isBlank()) {
            slides = presentations.decks().stream()
                .flatMap(deck -> presentations.deckSlides(deck.deckId()).stream())
                .toList();
        } else {
            List<String> coursewareKeys = studyroom.selectVerifiedCoursewareSourcePaths(chapterId).stream()
                .map(TextbookLessonIds::coursewareKey)
                .filter(key -> !key.isBlank())
                .distinct()
                .toList();
            slides = coursewareKeys.stream().flatMap(key -> presentations.slidesForCoursewareKey(key).stream()).toList();
        }
        return slides.stream()
            .filter(PresentationSlide::shouldShow)
            .map(slide -> new Candidate(
                StudyroomModelOutput.ResourceType.PPT,
                slide.id(),
                slide.title(),
                "课件=" + slide.deckId() + " 原始页码=" + slide.slideNumber() + " " + slide.semanticSummary() + " " + slide.rawText(),
                List.of()
            ))
            .toList();
    }

    private List<Candidate> animationCandidates(String chapterId) {
        String scope = chapterId == null || chapterId.isBlank() ? null : DsvpLocalEngine.chapterScope(chapterId);
        JsonNode response = animationEngine.capabilities(scope).orElse(null);
        if (response == null || !response.path("capabilities").isArray()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode capability : response.path("capabilities")) {
            String id = capability.path("capability").asString("");
            if (id.isBlank()) {
                continue;
            }
            Set<String> parameterKeys = new LinkedHashSet<>();
            addTextValues(parameterKeys, capability.path("requiredArguments"));
            addTextValues(parameterKeys, capability.path("optionalArguments"));
            candidates.add(new Candidate(
                StudyroomModelOutput.ResourceType.ANIMATION,
                id,
                capability.path("label").asString(id),
                capability.path("description").asString(""),
                List.copyOf(parameterKeys)
            ));
        }
        return List.copyOf(candidates);
    }

    private List<Candidate> codeCandidates(String chapterId) {
        List<Candidate> candidates = new ArrayList<>();
        for (ClassroomCodeLesson lesson : codeCatalog.lessons()) {
            if (chapterId != null && !chapterId.isBlank()
                && !chapterId.equals(lesson.coursewareKey())
                && !chapterId.equals(lesson.chapterId())
                && !lesson.coursewareKey().startsWith(chapterId + "-")) {
                continue;
            }
            for (ClassroomCodeSample sample : lesson.samples()) {
                candidates.add(new Candidate(
                    StudyroomModelOutput.ResourceType.C_CODE,
                    sample.id(),
                    sample.title(),
                    sample.summary() + " " + String.join(" ", sample.targets()) + " " + String.join(" ", sample.sections()),
                    List.of()
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private void addSafely(List<Candidate> target, String sourceName, CandidateSource source) {
        try {
            target.addAll(source.load());
        } catch (RuntimeException error) {
            LOGGER.warn("Studyroom {} candidates are unavailable: {}", sourceName, error.getMessage());
        }
    }

    public List<Map<String, String>> codeDetails(List<Candidate> selected) {
        if (codeCatalog == null) return List.of();
        var ids = selected.stream().filter(c -> c.type() == StudyroomModelOutput.ResourceType.C_CODE)
            .map(Candidate::id).collect(java.util.stream.Collectors.toSet());
        int perSample = Math.min(4000, 24000 / Math.max(1, ids.size()));
        return codeCatalog.lessons().stream().flatMap(l -> l.samples().stream()).filter(s -> ids.contains(s.id()))
            .map(s -> Map.of("id", s.id(), "source", s.code().substring(0, Math.min(perSample, s.code().length())),
                "sourceComplete", String.valueOf(s.code().length() <= perSample), "stdin", s.stdin(),
                "lineCount", String.valueOf(s.code().split("\\R", -1).length))).toList();
    }

    /**
     * Signed, extension-bearing page URL from the presentation catalog. Read models inject this;
     * the model never authors it, and stored resource JSON is not the cache address.
     */
    public String pptImageUrl(String slideId) {
        if (presentations == null || slideId == null || slideId.isBlank()) {
            return null;
        }
        PresentationSlide slide = presentations.slide(slideId);
        String url = slide == null ? null : slide.imageUrl();
        return url == null || url.isBlank() ? null : url;
    }

    public Map<String,Object> animationDemo(String id) {
        if(animationEngine==null)return Map.of();
        var response=animationEngine.capabilities(null).orElse(null);
        if(response==null)return Map.of();
        for(var capability:response.path("capabilities")) {
            if(!id.equals(capability.path("capability").asString("")))continue;
            Map<String,Object> result=new LinkedHashMap<>();
            capability.path("demoArguments").properties().forEach(entry -> result.put(entry.getKey(),
                new tools.jackson.databind.json.JsonMapper().convertValue(entry.getValue(),Object.class)));
            return Collections.unmodifiableMap(result);
        }
        return Map.of();
    }

    private static void addTextValues(Set<String> target, JsonNode node) {
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isString() && !value.asString().isBlank()) {
                    target.add(value.asString());
                }
            }
        }
    }

    private static String key(StudyroomModelOutput.ResourceType type, String id) {
        return type.value() + ":" + id;
    }

    @FunctionalInterface
    private interface CandidateSource {
        List<Candidate> load();
    }

    public record Candidate(
        StudyroomModelOutput.ResourceType type,
        String id,
        String title,
        String description,
        List<String> allowedParameterKeys
    ) {
        public Candidate {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            allowedParameterKeys = allowedParameterKeys == null ? List.of() : List.copyOf(allowedParameterKeys);
        }
    }
}
