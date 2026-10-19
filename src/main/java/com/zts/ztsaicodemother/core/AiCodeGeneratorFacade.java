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
import com.zts.ztsaicodemother.constant.AppConstant;
import com.zts.ztsaicodemother.core.builder.VueProjectBuilder;
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

    @Resource
    private VueProjectBuilder vueProjectBuilder;

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
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId, aiCodeGeneratorService);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId, aiCodeGeneratorService);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream, appId);
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
    private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
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
                        // 执行 Vue 项目构建（同步执行，确保预览时项目已就绪）
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                        vueProjectBuilder.buildProject(projectPath);
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
     * 多文件生成缺 CSS/JS 时，最多自动补生成几次
     */
    private static final int MAX_MULTI_FILE_RETRY = 1;

    /**
     * 处理流式生成的代码：边接收边拼接，流结束后解析并保存
     *
     * <p>MULTI_FILE 会额外做一层「缺什么补什么」：模型经常只输出一个 HTML 代码块（或只输出 HTML、
     * 把 CSS/JS 引用留在 HTML 里但不给内容），解析出来 css/js 为空时，会用同一份对话记忆再向模型
     * 要一次缺失的文件。补生成通过 Flux 串联执行，不能在完成回调里阻塞等待——SpringRestClient 的
     * 流式执行器是单线程的，阻塞会让补生成请求永远排不上队。
     *
     * @param codeStream              代码流
     * @param codeGenType             代码生成类型
     * @param appId                   应用 ID
     * @param aiCodeGeneratorService  AI 服务实例（与首轮同一实例，补生成复用它的对话记忆）
     * @return 原样透传首轮生成的代码流（补生成的内容不再透传，只用于补齐文件）
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId,
                                           AiCodeGeneratorService aiCodeGeneratorService) {
        StringBuilder codeBuilder = new StringBuilder();
        StringBuilder retryBuilder = new StringBuilder();
        Flux<String> pipeline = codeStream.doOnNext(codeBuilder::append);
        if (codeGenType == CodeGenTypeEnum.MULTI_FILE) {
            for (int attempt = 1; attempt <= MAX_MULTI_FILE_RETRY; attempt++) {
                // lambda 只能捕获 effectively final 的变量
                final int currentAttempt = attempt;
                pipeline = pipeline.concatWith(Flux
                        // 用 defer 保证补生成判断发生在首轮流结束、内容已累计之后
                        .defer(() -> retryMissingMultiFileFiles(mergeContent(codeBuilder, retryBuilder),
                                appId, aiCodeGeneratorService, currentAttempt))
                        .doOnNext(retryBuilder::append)
                        // 补生成的内容只用于补齐文件，不重复透传给前端
                        .filter(chunk -> false));
            }
        }
        return pipeline
                .doOnComplete(() -> {
                    // 流式返回完成后保存代码
                    String completeCode = mergeContent(codeBuilder, retryBuilder);
                    if (StrUtil.isBlank(completeCode)) {
                        log.error("模型没有返回任何内容，跳过解析与保存（appId={}, 类型={}）。"
                                        + "常见原因：thinking 占满输出预算、流被读超时中断、上游返回了空响应",
                                appId, codeGenType.getValue());
                        return;
                    }
                    saveCode(completeCode, codeGenType, appId);
                })
                .doOnError(throwable -> {
                    String partialCode = mergeContent(codeBuilder, retryBuilder);
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
     * 合并首轮生成结果与补生成结果。补生成内容追加在后面，而解析器取每种语言的第一个代码块，
     * 所以首轮的 HTML 会被保留，缺失的 CSS/JS 由补生成内容补上。
     *
     * @param codeBuilder  首轮生成内容
     * @param retryBuilder 补生成内容
     * @return 合并后的内容
     */
    private String mergeContent(StringBuilder codeBuilder, StringBuilder retryBuilder) {
        if (retryBuilder.length() == 0) {
            return codeBuilder.toString();
        }
        return codeBuilder.toString() + System.lineSeparator() + System.lineSeparator() + retryBuilder.toString();
    }

    /**
     * 缺 CSS/JS 时向模型补生成缺失的文件（包内可见，便于测试）
     *
     * @param content 目前已生成的内容
     * @param appId   应用 ID
     * @param service AI 服务实例
     * @param attempt 第几次补生成（从 1 开始）
     * @return 补生成的数据流；不缺失或无法解析时返回空流
     */
    Flux<String> retryMissingMultiFileFiles(String content, Long appId, AiCodeGeneratorService service, int attempt) {
        MultiFileCodeResult parsed = tryParseMultiFile(content);
        if (parsed == null) {
            return Flux.empty();
        }
        boolean needCss = StrUtil.isBlank(parsed.getCssCode());
        boolean needJs = StrUtil.isBlank(parsed.getJsCode());
        if (!needCss && !needJs) {
            return Flux.empty();
        }
        String correctiveMessage = buildCorrectiveMessage(needCss, needJs);
        log.warn("多文件生成缺少{}，第 {} 次向模型补生成（appId={}）",
                needCss && needJs ? " style.css 和 script.js" : (needCss ? " style.css" : " script.js"),
                attempt, appId);
        try {
            return service.generateMultiFileCodeStream(correctiveMessage);
        } catch (Exception e) {
            log.error("发起多文件补生成失败（appId={}）", appId, e);
            return Flux.empty();
        }
    }

    /**
     * 尝试按多文件类型解析内容，解析失败返回 null
     */
    private MultiFileCodeResult tryParseMultiFile(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        try {
            Object parsed = CodeParserExecutor.executeParser(content, CodeGenTypeEnum.MULTI_FILE);
            return parsed instanceof MultiFileCodeResult result ? result : null;
        } catch (Exception e) {
            log.warn("补生成前解析多文件内容失败，跳过补生成", e);
            return null;
        }
    }

    /**
     * 构造补生成用的用户消息（包内可见，便于测试）
     *
     * @param needCss 是否缺 CSS
     * @param needJs  是否缺 JS
     * @return 用户消息
     */
    static String buildCorrectiveMessage(boolean needCss, boolean needJs) {
        StringBuilder message = new StringBuilder("你上一次的回复不完整，只输出了 index.html");
        if (needCss && needJs) {
            message.append("，缺少 style.css 和 script.js 的代码。");
        } else if (needCss) {
            message.append("，缺少 style.css 的代码。");
        } else {
            message.append("，缺少 script.js 的代码。");
        }
        message.append("请只输出缺失的文件，每个文件一个 Markdown 代码块：");
        if (needCss) {
            message.append("用 css 语言标记输出 style.css 的完整内容；");
        }
        if (needJs) {
            message.append("用 javascript 语言标记输出 script.js 的完整内容；");
        }
        message.append("不要重复输出 HTML 代码块，要完整的可用代码，不要只输出片段。");
        return message.toString();
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
