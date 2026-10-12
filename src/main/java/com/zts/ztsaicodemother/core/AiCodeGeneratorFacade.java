package com.zts.ztsaicodemother.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zts.ztsaicodemother.ai.AiCodeGeneratorService;
import com.zts.ztsaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.zts.ztsaicodemother.ai.model.HtmlCodeResult;
import com.zts.ztsaicodemother.ai.model.MultiFileCodeResult;
import com.zts.ztsaicodemother.ai.model.message.AiResponseMessage;
import com.zts.ztsaicodemother.ai.model.message.ToolExecutedMessage;
import com.zts.ztsaicodemother.ai.model.message.ToolRequestMessage;
import com.zts.ztsaicodemother.core.parser.CodeParserExecutor;
import com.zts.ztsaicodemother.core.saver.CodeFileSaverExecutor;
import com.zts.ztsaicodemother.exception.BusinessException;
import com.zts.ztsaicodemother.exception.ErrorCode;
import com.zts.ztsaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * AI 代码生成门面类，组合代码生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage       用户提示词
     * @param codeGenTypeEnum   生成类型
     * @param appId 应用 ID
     * @return  保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        // 根据 appId 获取对应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage       用户提示词
     * @param codeGenTypeEnum   生成类型
     * @param appId 应用 ID
     * @return  保存的目录
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        // 根据 appId 获取对应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }

    /**
     * 处理流式生成的代码：边接收边拼接，流结束后解析并保存
     *
     * @param codeStream   代码流
     * @param codeGenType  代码生成类型
     * @param appId        应用 ID
     * @return 原样透传的代码流（错误不会被吞掉）
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    // 流式返回完成后保存代码
                    String completeCode = codeBuilder.toString();
                    if (StrUtil.isBlank(completeCode)) {
                        log.error("模型没有返回任何内容，跳过解析与保存（appId={}, 类型={}）。"
                                        + "常见原因：thinking 占满输出预算、流被读超时中断、上游返回了空响应",
                                appId, codeGenType.getValue());
                        return;
                    }
                    saveCode(completeCode, codeGenType, appId);
                })
                .doOnError(throwable -> {
                    String partialCode = codeBuilder.toString();
                    log.error("流式生成失败（已收到 {} 个字符，appId={}, 类型={}）。"
                                    + "常见原因：输出被 max-tokens 截断、读超时、上游报错或连接被关闭；"
                                    + "若异常是「completeResponse is null」的 NPE，说明整条流没有返回任何正文内容",
                            partialCode.length(), appId, codeGenType.getValue(), throwable);
                    // 出错时把已经收到的内容也落盘，尽量保留半成品，方便排查和续写
                    if (StrUtil.isNotBlank(partialCode)) {
                        saveCode(partialCode, codeGenType, appId);
                    }
                });
    }

    /**
     * 解析并保存代码，同时把模型的原始响应落盘，便于排查输出格式问题
     *
     * @param completeCode 模型返回的完整内容，或出错时已经收到的部分内容
     * @param codeGenType  代码生成类型
     * @param appId        应用 ID
     */
    private void saveCode(String completeCode, CodeGenTypeEnum codeGenType, Long appId) {
        // 原始响应落盘，出问题时可以直接查看模型到底输出了什么
        dumpRawContent(completeCode, appId);
        try {
            // 使用执行器解析代码
            Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
            // 多文件缺少 CSS / JS 时给出明确告警，避免只保存了一个 html 却不自知
            if (parsedResult instanceof MultiFileCodeResult multiFileCodeResult
                    && (StrUtil.isBlank(multiFileCodeResult.getCssCode())
                    || StrUtil.isBlank(multiFileCodeResult.getJsCode()))) {
                log.warn("多文件解析结果不完整：css={}, js={}（模型可能只输出了一部分，请看原始响应文件 {}）",
                        StrUtil.isBlank(multiFileCodeResult.getCssCode()) ? "缺失" : "正常",
                        StrUtil.isBlank(multiFileCodeResult.getJsCode()) ? "缺失" : "正常",
                        getRawContentPath(appId));
            }
            // 使用执行器保存代码
            File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
            log.info("保存成功，路径为：{}", saveDir.getAbsolutePath());
        } catch (Exception e) {
            // 打完整堆栈，避免只看到一句没有上下文的提示
            log.error("解析或保存代码失败（appId={}, 类型={}），原始响应已保存到 {}",
                    appId, codeGenType.getValue(), getRawContentPath(appId), e);
        }
    }

    /**
     * 把模型的原始响应保存到临时文件，便于排查解析问题
     *
     * @param content 原始响应内容
     * @param appId   应用 ID
     */
    private void dumpRawContent(String content, Long appId) {
        String path = getRawContentPath(appId);
        try {
            FileUtil.writeString(content, path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 落盘失败不能影响主流程
            log.warn("写入原始响应文件失败：{}", path, e);
        }
    }

    /**
     * 原始响应文件的路径
     *
     * @param appId 应用 ID
     * @return 文件路径
     */
    private String getRawContentPath(Long appId) {
        return System.getProperty("user.dir") + "/tmp/last_raw_" + appId + ".txt";
    }

}
