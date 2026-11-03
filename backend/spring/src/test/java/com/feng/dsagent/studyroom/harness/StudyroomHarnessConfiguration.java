package com.feng.dsagent.studyroom.harness;

import com.feng.dsagent.knowledge.service.KnowledgeSearchService;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelStreamHandler;

import com.feng.dsagent.aiquota.support.AiQuotaExecution;
import com.feng.dsagent.auth.service.UserAccountService;
import com.feng.dsagent.compiler.*;
import com.feng.dsagent.knowledge.*;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.studyroom.*;
import com.feng.dsagent.studyroom.draft.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import com.feng.dsagent.compiler.support.ClassroomCodeStudyroomCatalog;
import com.feng.dsagent.knowledge.entity.KnowledgeChunkEntity;
import com.feng.dsagent.studyroom.support.StudyroomModelOutput;
import com.feng.dsagent.studyroom.support.StudyroomResourceCatalog;
import com.feng.dsagent.studyroom.draft.domain.LearningDepth;
import com.feng.dsagent.studyroom.draft.domain.ReviewedOutline;

@TestConfiguration(proxyBeanMethods=false)
public class StudyroomHarnessConfiguration {
    @Bean @Primary KnowledgeSearchService harnessKnowledge() {
        return new KnowledgeSearchService(List.of(new KnowledgeChunkEntity("harness-stack",null,"栈的定义与实现",
            "公开合成教学样本：栈遵循后进先出。top指示栈顶，入栈增加元素，出栈移除栈顶元素。代码阅读必须以提供的源码为准。",
            "harness/public-stack","1","PUBLIC")),0);
    }

    @Bean @Primary StudyroomResourceCatalog harnessResources(ClassroomCodeStudyroomCatalog codes) {
        var sample=codes.lessons().stream().flatMap(l->l.samples().stream()).filter(s->s.id().equals("03-01-s1")).findFirst().orElseThrow();
        return new StudyroomResourceCatalog(null,null,null,null) {
            @Override public List<Candidate> candidates(String chapter) {return List.of(new Candidate(
                StudyroomModelOutput.ResourceType.C_CODE,sample.id(),"栈的 C 代码实现",sample.summary(),List.of()),new Candidate(StudyroomModelOutput.ResourceType.PPT,"harness-stack-slide","栈的定义与代码课件","公开合成样本，栈顶与后进先出",List.of()));}
            @Override public List<Map<String,String>> codeDetails(List<Candidate> selected) {
                return selected.stream().anyMatch(c->c.id().equals(sample.id()))
                    ?List.of(Map.of("id",sample.id(),"source",sample.code(),"sourceComplete","true","stdin",sample.stdin(),
                        "lineCount",String.valueOf(sample.code().split("\\R",-1).length))):List.of();
            }
            @Override public String pptImageUrl(String slideId) {
                return "harness-stack-slide".equals(slideId)
                    ? "/api/v1/presentation/slides/harness-stack-slide/image"
                    : null;
            }
        };
    }

    @Bean @Primary AiQuotaExecution harnessExecution(ObjectMapper mapper) {
        var failures=new ConcurrentHashMap<String,AtomicInteger>();
        AiQuotaExecution fixture=(user,operation,requestId,request)->{
            String system=request.messages().getFirst().content();
            String prompt=request.messages().getLast().content();
            if(system.contains("可选教学定位动作")) {
                var target=mapper.readTree(prompt).path("targets").get(0);
                if(target!=null&&"C_CODE".equals(target.path("materialKind").asString()))return new ModelResponse(mapper.writeValueAsString(Map.of("actions",List.of(Map.of("kind","CODE_HIGHLIGHT","sceneId",target.path("sceneId").asString(),"resourceId",target.path("resourceId").asString(),"startLine",1,"endLine",Math.min(3,target.path("lineCount").asInt()))))),15L);
                return new ModelResponse("{\"actions\":[]}",15L);
            }
            if(system.startsWith("只根据提供的已发布课程"))return new ModelResponse(mapper.writeValueAsString(Map.of("checks",java.util.stream.IntStream.range(0,3).mapToObj(i->Map.of("prompt","依次入栈1和"+(i+2)+"，首先弹出哪一个？","options",List.of(Map.of("id","a","text","1"),Map.of("id","b","text",String.valueOf(i+2))),"answerOption","b","analysis","后进入的元素先离开栈。")).toList())),30L);
            if(system.contains("学习设计师")) {
                var unit1=new ReviewedOutline.Unit("definition","栈是什么","理解栈的后进先出特性",List.of("stack.definition"),"EXPLAIN",List.of("CONCEPT"),List.of());
                var unit2=new ReviewedOutline.Unit("implementation","阅读 C 实现","对照栈源码理解基本操作",List.of("stack.implementation"),"EXPLAIN",List.of("IMPLEMENTATION"),
                    List.of(new ReviewedOutline.Material("C_CODE","REQUIRED","对照栈实现",List.of(),"UNVERIFIED")));
                var input=mapper.readTree(prompt);
                var outline=new ReviewedOutline(1,LearningDepth.valueOf(input.path("depth").asString()),"栈的定义与代码阅读",
                    List.of("理解栈并阅读基本操作源码"),List.of(),List.of(unit1,unit2),List.of(),List.of(),List.of("harness-stack"),null,"EVIDENCE_ONLY");
                return new ModelResponse(mapper.writeValueAsString(outline),100L);
            }
            if(system.contains("审查员"))return new ModelResponse("{\"valid\":true,\"reason\":\"内容与公开合成依据及所给源码相符\",\"issues\":[]}",30L);
            if(system.contains("只完成当前")) {
                int repair=prompt.indexOf("\n仅修复");if(repair>=0)prompt=prompt.substring(0,repair);
                var input=mapper.readTree(prompt);String activity=input.path("goal").path("activity").asString();
                String question=input.path("context").path("question").asString("");
                if(question.contains("等待")&&activity.equals("CONCEPT"))try{Thread.sleep(1800);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                if(question.contains("故障")&&activity.equals("IMPLEMENTATION")
                    &&failures.computeIfAbsent(question,k->new AtomicInteger()).getAndIncrement()<2)
                    return new ModelResponse("not-json",20L);
                var scenes=new ArrayList<Map<String,Object>>();
                input.path("candidates").properties().forEach(entry->{
                    if(activity.equals("IMPLEMENTATION")&&"C_CODE".equals(entry.getValue().path("type").asString()))scenes.add(Map.of("resourceKey",entry.getKey(),"explanation","阅读所给栈源码，关注基本操作与栈顶变化。","startLine",1,"endLine",3));
                    if(question.contains("讲述框选回归")&&"PPT".equals(entry.getValue().path("type").asString()))scenes.add(Map.of("resourceKey",entry.getKey(),"explanation","请看课件上的栈顶指针。"));
                });
                return new ModelResponse(mapper.writeValueAsString(Map.of("title",activity.equals("IMPLEMENTATION")?"栈的 C 实现":"理解后进先出",
                    "explanation",activity.equals("IMPLEMENTATION")?"结合下面真实源码阅读栈的基本操作，不把预期行为当成已经运行的输出。":"栈是一种后进先出的结构，最后进入的元素最先离开。",
                    "evidenceKeys",List.of("e1"),"scenes",scenes,"checks",List.of(Map.of("prompt","依次入栈1和2，首先弹出哪一个？","options",List.of(Map.of("id","a","text","1"),Map.of("id","b","text","2")),"answerOption","b","analysis","后进入的2先离开栈。")))),80L);
            }
            return new ModelResponse(prompt.contains("断线恢复回归")?"结合当前资料，栈遵循后进先出。".repeat(3):"结合当前资料，栈遵循后进先出。",30L);
        };
        return new AiQuotaExecution() {
            public ModelResponse complete(Long user,String operation,String requestId,com.feng.dsagent.model.ModelRequest request) {return fixture.complete(user,operation,requestId,request);}
            public void stream(Long user,String operation,String requestId,com.feng.dsagent.model.ModelRequest request,com.feng.dsagent.model.ModelStreamHandler handler) {
                var response=fixture.complete(user,operation,requestId,request);
                String text=response.content();
                boolean slow=request.messages().getLast().content().contains("断线恢复回归");int chunk=slow?4:40;
                for(int i=0;i<text.length();i+=chunk) {
                    handler.onContent(text.substring(i,Math.min(i+chunk,text.length())));
                    try{Thread.sleep(slow?180:60);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                handler.onUsage(response.totalTokens());
            }
        };
    }

    @Bean ApplicationRunner harnessAccount(UserAccountService users,PasswordEncoder passwords) {
        return args->{if(users.findByEmail("harness@structify.test").isEmpty())
            users.create("harness@structify.test","HarnessStudent",passwords.encode("HarnessOnly123!"),Set.of("STUDENT"));};
    }
}
