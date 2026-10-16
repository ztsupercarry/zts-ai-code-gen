package com.zts.ztsaicodemother.langgraph4j.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.zts.ztsaicodemother.exception.BusinessException;
import com.zts.ztsaicodemother.exception.ErrorCode;
import com.zts.ztsaicodemother.langgraph4j.model.ImageResource;
import com.zts.ztsaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import com.zts.ztsaicodemother.manager.CosManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mermaid 架构图生成工具
 */
@Slf4j
@Component
public class MermaidDiagramTool {

    @Resource
    private CosManager cosManager;

    @Tool("将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系")
    public List<ImageResource> generateMermaidDiagram(@P("Mermaid 图表代码") String mermaidCode,
                                                      @P("架构图描述") String description) {
        if (StrUtil.isBlank(mermaidCode)) {
            return new ArrayList<>();
        }
        try {
            // 转换为SVG图片
            File diagramFile = convertMermaidToSvgWithSpecifiedChromePath(mermaidCode);
            // 上传到COS
            String keyName = String.format("/mermaid/%s/%s",
                    RandomUtil.randomString(5), diagramFile.getName());
            String cosUrl = cosManager.uploadFile(keyName, diagramFile);
            // 清理临时文件
            FileUtil.del(diagramFile);
            if (StrUtil.isNotBlank(cosUrl)) {
                return Collections.singletonList(ImageResource.builder()
                        .category(ImageCategoryEnum.ARCHITECTURE)
                        .description(description)
                        .url(cosUrl)
                        .build());
            }
        } catch (Exception e) {
            log.error("生成架构图失败: {}", e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    /**
     * 将Mermaid代码转换为SVG图片
     */
    private File convertMermaidToSvg(String mermaidCode) {
        // 创建临时输入文件
        File tempInputFile = FileUtil.createTempFile("mermaid_input_", ".mmd", true);
        FileUtil.writeUtf8String(mermaidCode, tempInputFile);
        // 创建临时输出文件
        File tempOutputFile = FileUtil.createTempFile("mermaid_output_", ".svg", true);
        // 根据操作系统选择命令
        String command = SystemUtil.getOsInfo().isWindows() ? "mmdc.cmd" : "mmdc";
        // 构建命令
        String cmdLine = String.format("%s -i %s -o %s -b transparent",
                command,
                tempInputFile.getAbsolutePath(),
                tempOutputFile.getAbsolutePath()
        );
        // 执行命令
        RuntimeUtil.execForStr(cmdLine);
        // 检查输出文件
        if (!tempOutputFile.exists() || tempOutputFile.length() == 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行失败");
        }
        // 清理输入文件，保留输出文件供上传使用
        FileUtil.del(tempInputFile);
        return tempOutputFile;
    }

    /**
     * 将Mermaid代码转换为SVG图片, 指定 chromePath 路径
     * @param mermaidCode
     * @return
     */
    private File convertMermaidToSvgWithSpecifiedChromePath(String mermaidCode) {
        File tempInputFile = FileUtil.createTempFile("mermaid_input_", ".mmd", true);
        FileUtil.writeUtf8String(mermaidCode, tempInputFile);

        File tempOutputFile = FileUtil.createTempFile("mermaid_output_", ".svg", true);
        String command = SystemUtil.getOsInfo().isWindows() ? "mmdc.cmd" : "mmdc";
        String chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";

        try {
            // 用 ProcessBuilder 设置环境变量
            ProcessBuilder pb = new ProcessBuilder(
                    command,
                    "-i", tempInputFile.getAbsolutePath(),
                    "-o", tempOutputFile.getAbsolutePath(),
                    "-b", "transparent"
            );

            // 加环境变量 Puppeteer 执行路径
            pb.environment().put("PUPPETEER_EXECUTABLE_PATH", chromePath);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行失败，exit=" + exitCode);
            }

            if (!tempOutputFile.exists() || tempOutputFile.length() == 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 没有生成输出文件");
            }
            return tempOutputFile;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成架构图失败: " + e.getMessage());
        } finally {
            FileUtil.del(tempInputFile);
        }
    }
}
