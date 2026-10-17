package com.zts.ztsaicodemother.langgraph4j;

import com.zts.ztsaicodemother.exception.BusinessException;
import com.zts.ztsaicodemother.exception.ErrorCode;
import com.zts.ztsaicodemother.langgraph4j.model.QualityResult;
import com.zts.ztsaicodemother.langgraph4j.node.*;
import com.zts.ztsaicodemother.langgraph4j.state.WorkflowContext;
import com.zts.ztsaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;


/**
 * 代码生成工作流（实际可用）
 */
@Slf4j
public class CodeGenWorkflow {

    /**
     * 创建完整的工作流
     */
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            return new MessagesStateGraph<String>()
                    // 添加节点 - 使用完整实现的节点
                    .addNode("image_collector", ImageCollectorNode.create())
                    .addNode("prompt_enhancer", PromptEnhancerNode.create())
                    .addNode("router", RouterNode.create())
                    .addNode("code_generator", CodeGeneratorNode.create())
                    .addNode("code_quality_check", CodeQualityCheckNode.create())
                    .addNode("project_builder", ProjectBuilderNode.create())

                    // 添加边
                    .addEdge(START, "image_collector")
                    .addEdge("image_collector", "prompt_enhancer")
                    .addEdge("prompt_enhancer", "router")
                    .addEdge("router", "code_generator")
                    .addEdge("code_generator", "code_quality_check")
                    // 新增质检条件边：根据质检结果决定下一步
                    .addConditionalEdges("code_quality_check",
                            edge_async(this::routeAfterQualityCheck),
                            Map.of(
                                    "build", "project_builder",   // 质检通过且需要构建
                                    "skip_build", END,            // 质检通过但跳过构建
                                    "fail", "code_generator",     // 质检失败，重新生成
                                    "give_up", END                // 质检连续失败超过上限，终止工作流
                            ))
                    .addEdge("project_builder", END)

                    // 编译工作流
                    .compile();
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流创建失败");
        }
    }

    /**
     * 执行工作流
     */
    public WorkflowContext executeWorkflow(String originalPrompt) {
        CompiledGraph<MessagesState<String>> workflow = createWorkflow();

        // 初始化 WorkflowContext
        WorkflowContext initialContext = WorkflowContext.builder()
                .originalPrompt(originalPrompt)
                .currentStep("初始化")
                .build();

        GraphRepresentation graph = workflow.getGraph(GraphRepresentation.Type.MERMAID);
        log.info("工作流图:\n{}", graph.content());
        log.info("开始执行代码生成工作流");

        WorkflowContext finalContext = null;
        int stepCounter = 1;
        for (NodeOutput<MessagesState<String>> step : workflow.stream(
                Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, initialContext))) {
            log.info("--- 第 {} 步完成 ---", stepCounter);
            // 显示当前状态
            WorkflowContext currentContext = WorkflowContext.getContext(step.state());
            if (currentContext != null) {
                finalContext = currentContext;
                log.info("当前步骤上下文: {}", currentContext);
            }
            stepCounter++;
        }
        log.info("代码生成工作流执行完成！");
        return finalContext;
    }

    /**
     * 质检连续失败多少次后终止工作流（首次生成算第 1 次检查，因此最多重新生成 MAX_QUALITY_CHECK_RETRY 次）
     */
    private static final int MAX_QUALITY_CHECK_RETRY = 2;

    /**
     * 根据质检结果决定下一步
     *
     * @param state
     * @return
     */
    private String routeAfterQualityCheck(MessagesState<String> state) {
        return decideRouteAfterQualityCheck(WorkflowContext.getContext(state));
    }

    /**
     * 质检结果的路由决策（包内可见，便于单测）
     *
     * <p>质检失败时不能无条件回到 code_generator：一旦失败原因是确定性的（例如目录路径错误、
     * 读不到代码文件），重新生成永远无法修复，工作流会无限循环。这里用上下文里的累计失败次数兜底，
     * 超过上限直接返回 give_up 终止工作流。
     *
     * @param context 工作流上下文
     * @return build / skip_build / fail / give_up
     */
    static String decideRouteAfterQualityCheck(WorkflowContext context) {
        QualityResult qualityResult = context == null ? null : context.getQualityResult();
        boolean passed = qualityResult != null && Boolean.TRUE.equals(qualityResult.getIsValid());
        if (!passed) {
            int retryCount = context == null ? 0 : context.getQualityCheckRetryCount();
            if (retryCount > MAX_QUALITY_CHECK_RETRY) {
                String message = "代码质检连续失败 " + retryCount + " 次，超过上限 " + MAX_QUALITY_CHECK_RETRY
                        + "，终止工作流（避免「质检失败 → 重新生成」无限循环）";
                if (context != null) {
                    context.setErrorMessage(message);
                }
                log.error(message);
                return "give_up";
            }
            log.error("代码质检失败，需要重新生成代码（累计第 {} 次，上限 {} 次）", retryCount, MAX_QUALITY_CHECK_RETRY);
            return "fail";
        }
        // 质检通过，使用原有的构建路由逻辑
        log.info("代码质检通过，继续后续流程");
        return routeBuildOrSkip(context);
    }

    /**
     * 质检通过后决定构建还是跳过构建
     *
     * @param context 工作流上下文
     * @return build / skip_build
     */
    private static String routeBuildOrSkip(WorkflowContext context) {
        CodeGenTypeEnum generationType = context.getGenerationType();
        // HTML 和 MULTI_FILE 类型不需要构建，直接结束
        if (generationType == CodeGenTypeEnum.HTML || generationType == CodeGenTypeEnum.MULTI_FILE) {
            return "skip_build";
        }
        // VUE_PROJECT 需要构建
        return "build";
    }
}




















