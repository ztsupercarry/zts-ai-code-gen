package com.zts.ztsaicodemother.core.parser;

import cn.hutool.core.util.StrUtil;
import com.zts.ztsaicodemother.ai.model.MultiFileCodeResult;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器（HTML + CSS + JS）
 *
 * <p>除了按 markdown 代码块提取外，还带一个兜底：模型经常不遵守「输出三个代码块」的约定，
 * 而是把 CSS / JavaScript 内联在唯一的 HTML 代码块里（甚至只输出一个 html 代码块）。
 * 这种情况下会把内联的 {@code <style>} / {@code <script>} 抽出来当作 style.css / script.js，
 * 并把 index.html 改成用 {@code <link>} / {@code <script src>} 引用它们，
 * 保证 MULTI_FILE 真的落盘三个文件，而不是静默只剩一个 index.html。
 */
@Slf4j
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_CODE_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** HTML 里内联的 style 块 */
    private static final Pattern INLINE_STYLE_PATTERN = Pattern.compile("<style[^>]*>([\\s\\S]*?)</style>", Pattern.CASE_INSENSITIVE);
    /** HTML 里内联的 script 块（带 src 的外链脚本不会被匹配） */
    private static final Pattern INLINE_SCRIPT_PATTERN = Pattern.compile("<script(?![^>]*\\bsrc\\s*=)([^>]*)>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
    /** script 标签上的 type 属性 */
    private static final Pattern SCRIPT_TYPE_PATTERN = Pattern.compile("\\btype\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern STYLE_LINK_PATTERN = Pattern.compile("<link[^>]*href\\s*=\\s*[\"']style\\.css[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile("<script[^>]*src\\s*=\\s*[\"']script\\.js[\"'][^>]*>\\s*</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_END_PATTERN = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_START_PATTERN = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_END_PATTERN = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE);

    private static final String STYLE_LINK_TAG = "    <link rel=\"stylesheet\" href=\"style.css\">\n";
    private static final String SCRIPT_TAG = "    <script src=\"script.js\"></script>\n";

    /** 可以被当作 JavaScript 搬运的 script type */
    private static final String JS_TYPE_REGEX = "(?i)^(text/javascript|application/javascript|text/ecmascript|application/ecmascript|module)$";

    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        String htmlCode = extractCodeByPattern(codeContent, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(codeContent, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(codeContent, JS_CODE_PATTERN);
        if (StrUtil.isBlank(htmlCode)) {
            return result;
        }

        // 兜底 1：模型把 CSS 内联在 HTML 里
        if (StrUtil.isBlank(cssCode)) {
            ExtractResult extracted = extractInline(htmlCode, INLINE_STYLE_PATTERN, 1, false);
            if (extracted != null) {
                cssCode = extracted.content();
                htmlCode = ensureStyleLink(extracted.remainingHtml());
                log.warn("多文件解析兜底：HTML 中没有独立的 css 代码块，已把内联 <style> 抽取为 style.css");
            }
        }
        // 兜底 2：模型把 JavaScript 内联在 HTML 里
        if (StrUtil.isBlank(jsCode)) {
            ExtractResult extracted = extractInline(htmlCode, INLINE_SCRIPT_PATTERN, 2, true);
            if (extracted != null) {
                jsCode = extracted.content();
                htmlCode = extracted.remainingHtml();
                log.warn("多文件解析兜底：HTML 中没有独立的 javascript 代码块，已把内联 <script> 抽取为 script.js");
            }
        }
        // 不管代码块是模型直接给的、还是兜底拆出来的（甚至是被门面类补生成补齐的），
        // 最终都保证 HTML 引用了这两个文件，否则页面加载时引不到样式和脚本
        htmlCode = ensureReferences(htmlCode, cssCode, jsCode);
        if (StrUtil.isBlank(cssCode) && StrUtil.isBlank(jsCode)) {
            log.warn("多文件解析结果仍缺少 css 与 js：模型未输出对应代码块，HTML 里也没有可抽取的内联样式/脚本");
        }

        if (StrUtil.isNotBlank(htmlCode)) {
            result.setHtmlCode(htmlCode.trim());
        }
        if (StrUtil.isNotBlank(cssCode)) {
            result.setCssCode(cssCode.trim());
        }
        if (StrUtil.isNotBlank(jsCode)) {
            result.setJsCode(jsCode.trim());
        }
        return result;
    }

    /**
     * 从 HTML 中抽出一类内联块（style 或 script），并把它们从 HTML 里删掉
     *
     * @param html              HTML 内容
     * @param pattern           匹配模式
     * @param contentGroup      内容所在的分组下标
     * @param filterJavascript  是否只处理 JavaScript 类型的 script（跳过 JSON-LD 之类的数据块）
     * @return 抽取结果，没有匹配时返回 null
     */
    private ExtractResult extractInline(String html, Pattern pattern, int contentGroup, boolean filterJavascript) {
        Matcher matcher = pattern.matcher(html);
        StringBuilder content = new StringBuilder();
        StringBuilder remaining = new StringBuilder();
        boolean found = false;
        int lastEnd = 0;
        while (matcher.find()) {
            if (filterJavascript && !isJavascript(m(matcher.group(1)))) {
                // 不是 JavaScript（例如 application/ld+json），保留在 HTML 里
                continue;
            }
            String block = m(matcher.group(contentGroup));
            if (StrUtil.isBlank(block)) {
                continue;
            }
            content.append(block.trim()).append('\n');
            remaining.append(html, lastEnd, matcher.start());
            lastEnd = matcher.end();
            found = true;
        }
        if (!found) {
            return null;
        }
        remaining.append(html, lastEnd, html.length());
        return new ExtractResult(content.toString().trim(), remaining.toString());
    }

    /**
     * 保证 HTML 里引用了 style.css / script.js（对应文件有内容时才补）
     */
    private String ensureReferences(String htmlCode, String cssCode, String jsCode) {
        String html = htmlCode == null ? "" : htmlCode;
        if (StrUtil.isNotBlank(cssCode)) {
            html = ensureStyleLink(html);
        }
        if (StrUtil.isNotBlank(jsCode)) {
            html = ensureScriptTag(html);
        }
        return html;
    }

    /**
     * 确保 HTML 里有对 style.css 的引用
     */
    private String ensureStyleLink(String html) {
        if (STYLE_LINK_PATTERN.matcher(html).find()) {
            return html;
        }
        Matcher headEnd = HEAD_END_PATTERN.matcher(html);
        if (headEnd.find()) {
            return html.substring(0, headEnd.start()) + STYLE_LINK_TAG + html.substring(headEnd.start());
        }
        Matcher headStart = HEAD_START_PATTERN.matcher(html);
        if (headStart.find()) {
            return html.substring(0, headStart.end()) + "\n" + STYLE_LINK_TAG + html.substring(headStart.end());
        }
        return STYLE_LINK_TAG + html;
    }

    /**
     * 确保 HTML 里有对 script.js 的引用
     */
    private String ensureScriptTag(String html) {
        if (SCRIPT_SRC_PATTERN.matcher(html).find()) {
            return html;
        }
        Matcher bodyEnd = BODY_END_PATTERN.matcher(html);
        if (bodyEnd.find()) {
            return html.substring(0, bodyEnd.start()) + SCRIPT_TAG + html.substring(bodyEnd.start());
        }
        return html + "\n" + SCRIPT_TAG;
    }

    /**
     * script 标签的 type 是否属于 JavaScript（没有 type 属性时默认是 JavaScript）
     */
    private boolean isJavascript(String attributes) {
        if (StrUtil.isBlank(attributes)) {
            return true;
        }
        Matcher typeMatcher = SCRIPT_TYPE_PATTERN.matcher(attributes);
        if (!typeMatcher.find()) {
            return true;
        }
        return m(typeMatcher.group(1)).matches(JS_TYPE_REGEX);
    }

    /**
     * 根据正则模式提取代码
     *
     * @param content 原始内容
     * @param pattern 正则模式
     * @return 提取的代码
     */
    private String extractCodeByPattern(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * null 安全的 trim
     */
    private static String m(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 内联块抽取结果
     *
     * @param content       抽取出来的代码
     * @param remainingHtml 删除内联块之后的 HTML
     */
    private record ExtractResult(String content, String remainingHtml) {
    }
}
