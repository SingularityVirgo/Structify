package com.feng.dsagent.studyroom.generation.worker;


import com.feng.dsagent.studyroom.error.StudyroomErrorCode;
import com.feng.dsagent.model.exception.ModelClientException;
import com.feng.dsagent.studyroom.checks.service.ClassroomCheckService;
import com.feng.dsagent.studyroom.draft.service.OutlineService;
import com.feng.dsagent.studyroom.events.service.ClassroomEventService;
import com.feng.dsagent.studyroom.fragment.service.FragmentPublicationService;
import com.feng.dsagent.studyroom.generation.service.GenerationCallService;
import com.feng.dsagent.studyroom.generation.service.GenerationJobService;
import com.feng.dsagent.studyroom.questions.worker.ClassroomQuestionWorker;

import com.feng.dsagent.common.exception.BizException;
import com.feng.dsagent.studyroom.draft.domain.LearningDepth;
import com.feng.dsagent.studyroom.draft.mapper.OutlineMapper;
import com.feng.dsagent.studyroom.draft.support.OutlineJson;
import com.feng.dsagent.studyroom.draft.support.OutlineModelGateway;
import com.feng.dsagent.studyroom.fragment.domain.FragmentPlan;
import com.feng.dsagent.studyroom.fragment.support.FragmentComposer;
import com.feng.dsagent.studyroom.fragment.support.FragmentResourceResolver;
import com.feng.dsagent.studyroom.fragment.worker.PreparationScheduler;
import com.feng.dsagent.studyroom.generation.config.GenerationProperties;
import com.feng.dsagent.studyroom.generation.support.GenerationJobTiming;
import com.feng.dsagent.studyroom.generation.support.GenerationLease;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Component
public class StudyroomGenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(StudyroomGenerationWorker.class);
    private final GenerationJobService jobs;
    private final OutlineMapper outlineRows;
    private final OutlineService outlines;
    private final OutlineModelGateway model;
    private final FragmentComposer composer;
    private final FragmentResourceResolver resources;
    private final FragmentPublicationService publication;
    private final PreparationScheduler scheduler;
    private final OutlineJson json;
    private final boolean enabled;
    /** Named single-thread heartbeat; lease renewals must not share the virtual-thread job executor. */
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("studyroom-heartbeat").factory());
    /** Virtual-thread-per-task for generation jobs; concurrency capped by {@link #capacity}. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity;
    private ClassroomEventService events;
    @org.springframework.beans.factory.annotation.Autowired
    public void events(ClassroomEventService events) {this.events=events;}
    private ClassroomQuestionWorker questions;
    @org.springframework.beans.factory.annotation.Autowired
    public void questions(ClassroomQuestionWorker questions) {this.questions=questions;}
    private ClassroomCheckService checks;
    private GenerationCallService calls;
    @org.springframework.beans.factory.annotation.Autowired
    public void checks(ClassroomCheckService checks,GenerationCallService calls){this.checks=checks;this.calls=calls;}

    public StudyroomGenerationWorker(
            GenerationJobService jobs,
            OutlineMapper outlineRows,
            OutlineService outlines,
            OutlineModelGateway model,
            FragmentComposer composer,
            FragmentResourceResolver resources,
            FragmentPublicationService publication,
            PreparationScheduler scheduler,
            OutlineJson json,
            GenerationProperties properties) {
        this.jobs = jobs;
        this.outlineRows = outlineRows;
        this.outlines = outlines;
        this.model = model;
        this.composer = composer;
        this.resources = resources;
        this.publication = publication;
        this.scheduler = scheduler;
        this.json = json;
        this.enabled = properties.enabled();
        this.capacity = new Semaphore(properties.capacity());
    }

    @Scheduled(fixedDelayString = "${app.studyroom-generation.poll-interval-ms:1000}")
    public void poll() {
        if (!enabled || !capacity.tryAcquire()) return;
        executor.submit(
                () -> {
                    try {
                        runOnce();
                    } finally {
                        capacity.release();
                    }
                });
    }

    public boolean runOnce() {
        jobs.recover();
        Instant claimedAt = Instant.now();
        var job = jobs.claim(claimedAt);
        if (job == null) return false;
        long queueWaitMs = GenerationJobTiming.queueWaitMs(job, claimedAt);
        log.info("Studyroom job claimed job={} type={} queueWaitMs={}", job.get("id"), job.get("job_type"), queueWaitMs);
        var pulse =
                heartbeat.scheduleAtFixedRate(() -> jobs.heartbeat(job), 15, 15, TimeUnit.SECONDS);
        String outcome = "SUCCEEDED";
        long started = System.nanoTime();
        try {
            GenerationLease.enter(job);
            long user = ((Number) job.get("user_id")).longValue();
            if("FOLLOWUP".equals(job.get("job_type"))) {
                questions.run(job);
            } else if("CHECK".equals(job.get("job_type"))) {
                checks.run(job,calls);
            } else if ("FRAGMENT".equals(job.get("job_type"))) {
                var fragment=jobs.mapper().selectFragmentForGeneration(job.get("fragment_id"));
                OutlineJson.require(fragment!=null, StudyroomErrorCode.FRAGMENT_NOT_FOUND_CONFLICT);
                jobs.mapper().markFragmentGenerating(fragment.get("id"));
                jobs.mapper().touchRun(job.get("run_id"));
                var plan=json.read((String)fragment.get("plan_json"),FragmentPlan.class);
                long phaseAt = System.nanoTime();
                if(events!=null)events.runUpdate(job,"phase",Map.of("phase","PREPARING_RESOURCES","fragmentId",fragment.get("id")));
                var resolved=resources.resolve((String)fragment.get("question"),(String)fragment.get("chapter_id"),plan);
                var context=Map.of("unit",json.value((String)fragment.get("unit_spec_json")),"question",fragment.get("question"));
                var payload=json.object((String)job.get("payload_json"));
                payload.put("executionContext",Map.of("objective",plan.objective(),"resourceRevision",resolved.revision(),"planHash",fragment.get("plan_hash")));
                jobs.mapper().updateJobPayload(json.write(payload),job.get("id"));
                log.info("Studyroom job phase job={} phase=PREPARING_RESOURCES elapsedMs={}", job.get("id"),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseAt));
                phaseAt = System.nanoTime();
                if(events!=null)events.runUpdate(job,"phase",Map.of("phase","GENERATING_CONTENT","fragmentId",fragment.get("id")));
                String raw=composer.compose(user,(String)job.get("id"),plan,resolved,context);
                log.info("Studyroom job phase job={} phase=GENERATING_CONTENT elapsedMs={}", job.get("id"),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseAt));
                phaseAt = System.nanoTime();
                if(events!=null)events.runUpdate(job,"phase",Map.of("phase","PUBLISHING","fragmentId",fragment.get("id")));
                publication.publish(job,raw,resolved.revision());
                scheduler.reconcile(user,(String)job.get("run_id"));
                log.info("Studyroom job phase job={} phase=PUBLISHING elapsedMs={}", job.get("id"),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseAt));
            } else if(Set.of("PLAN","REVISE").contains((String)job.get("job_type"))) {
                if(events!=null)events.beginOutline(job);
                var draft = outlineRows.selectOwnedDraft((String) job.get("draft_id"), user, false);
                if (draft == null)
                    throw OutlineJson.error(StudyroomErrorCode.OUTLINE_NOT_FOUND);
                int base = OutlineJson.number(draft, "latest_revision");
                String previous =
                        base == 0
                                ? ""
                                : (String)
                                        outlineRows
                                                .selectRevision((String) draft.get("id"), base)
                                                .get("outline_json");
                var payload = json.object((String) job.get("payload_json"));
                String raw =
                        model.plan(
                                user,
                                (String) draft.get("question"),
                                LearningDepth.valueOf((String) draft.get("depth")),
                                previous,
                                (String) payload.get("feedback"),
                                (String) job.get("id"));
                outlines.complete(job, raw);
            } else {
                throw new IllegalArgumentException("UNSUPPORTED_JOB_TYPE");
            }
        } catch (RuntimeException error) {
            outcome = "FAILED";
            log.warn(
                            "Studyroom job failed: job={} error={} at={}",
                            job.get("id"),
                            error.getClass().getSimpleName(),
                            error.getStackTrace().length == 0 ? "" : error.getStackTrace()[0]);
            String code =
                    error instanceof BizException api
                            ? api.code()
                            : error instanceof IllegalArgumentException
                                    ? "STUDYROOM_CONTENT_INVALID"
                                    : "STUDYROOM_GENERATION_FAILED";
            if(error instanceof ModelClientException modelError)code=modelError.code();
            if(error instanceof IllegalArgumentException&&error.getMessage()!=null) {
                String prefix=error.getMessage().split("[:\\s]",2)[0];
                if(Set.of("FRAGMENT_JSON_INVALID","FRAGMENT_SCHEMA_INVALID","RESOURCE_BINDING_INVALID","EVIDENCE_INVALID",
                    "REQUIRED_RESOURCE_MISSING","CODE_LINE_OUT_OF_RANGE","CODE_SOURCE_UNAVAILABLE","CODE_EXECUTION_UNAVAILABLE",
                    "REVIEW_UNCERTAIN","TEACHING_BLOCKED").contains(prefix))code=prefix;
            }
            String message =
                    error instanceof BizException
                            ? error.getMessage()
                            : error instanceof IllegalArgumentException
                                    ? "这段内容未通过检查，已就绪内容会保留。可重新准备当前片段，或调整大纲。"
                                    : "生成未完成，请重试。此前已完成的内容会保留。";
            if(events!=null && Set.of("PLAN","REVISE").contains((String)job.get("job_type")) && jobs.active(job)) {
                try {events.outlineUpdate(job,"failed",Map.of("code",code,"message",message),true);}
                catch(RuntimeException eventFailure) {log.warn("Unable to record outline failure event for job {}",job.get("id"));}
            }
            if(events!=null&&"FRAGMENT".equals(job.get("job_type"))&&jobs.active(job)) {
                try {events.runUpdate(job,"failed",Map.of("code",code,"message",message,"fragmentId",job.get("fragment_id")));}
                catch(RuntimeException inaccessible){log.warn("Unable to record fragment failure event for job {}",job.get("id"));}
            }
            jobs.fail(job, code, message);
        } finally {
            log.info("Studyroom job finished job={} type={} outcome={} queueWaitMs={} executionMs={}",
                    job.get("id"), job.get("job_type"), outcome, queueWaitMs,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            GenerationLease.clear();
            pulse.cancel(false);
        }
        return true;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        heartbeat.shutdownNow();
    }
}
