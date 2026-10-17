package com.zts.ztsaicodemother.langgraph4j;

import com.zts.ztsaicodemother.langgraph4j.model.QualityResult;
import com.zts.ztsaicodemother.langgraph4j.state.WorkflowContext;
import com.zts.ztsaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 质检重试上限的纯逻辑单测（不启动 Spring、不调用模型）
 */
class CodeGenWorkflowRetryLimitTest {

    @Test
    void failedWithinLimitStillRetries() {
        WorkflowContext firstFailure = context(CodeGenTypeEnum.MULTI_FILE, failResult(), 1);
        assertEquals("fail", CodeGenWorkflow.decideRouteAfterQualityCheck(firstFailure));

        WorkflowContext secondFailure = context(CodeGenTypeEnum.MULTI_FILE, failResult(), 2);
        assertEquals("fail", CodeGenWorkflow.decideRouteAfterQualityCheck(secondFailure));
    }

    @Test
    void failedBeyondLimitGivesUpInsteadOfLooping() {
        WorkflowContext context = context(CodeGenTypeEnum.MULTI_FILE, failResult(), 3);
        assertEquals("give_up", CodeGenWorkflow.decideRouteAfterQualityCheck(context));
        assertNotNull(context.getErrorMessage());
    }

    @Test
    void nullQualityResultAlsoGivesUpWhenRetriesExhausted() {
        WorkflowContext context = context(CodeGenTypeEnum.MULTI_FILE, null, 3);
        assertEquals("give_up", CodeGenWorkflow.decideRouteAfterQualityCheck(context));
    }

    @Test
    void passedKeepsOriginalRouting() {
        WorkflowContext multiFile = context(CodeGenTypeEnum.MULTI_FILE, passResult(), 0);
        assertEquals("skip_build", CodeGenWorkflow.decideRouteAfterQualityCheck(multiFile));

        WorkflowContext vueProject = context(CodeGenTypeEnum.VUE_PROJECT, passResult(), 0);
        assertEquals("build", CodeGenWorkflow.decideRouteAfterQualityCheck(vueProject));

        // 通过后不应留下错误信息
        assertNull(multiFile.getErrorMessage());
    }

    @Test
    void workflowCompilesWithGiveUpBranch() {
        // 顺带校验条件边的 give_up 分支目标合法（编译期会校验节点映射，不需要调用模型）
        assertNotNull(new CodeGenWorkflow().createWorkflow());
    }

    @Test
    void concurrentWorkflowCompilesWithGiveUpBranch() {
        // 并发工作流同样要有 give_up 分支，且与串行工作流共用同一套重试上限
        assertNotNull(new CodeGenConcurrentWorkflow().createWorkflow());
    }

    private WorkflowContext context(CodeGenTypeEnum type, QualityResult qualityResult, int retryCount) {
        return WorkflowContext.builder()
                .generationType(type)
                .qualityResult(qualityResult)
                .qualityCheckRetryCount(retryCount)
                .build();
    }

    private QualityResult passResult() {
        return QualityResult.builder().isValid(true).build();
    }

    private QualityResult failResult() {
        return QualityResult.builder().isValid(false).build();
    }
}
