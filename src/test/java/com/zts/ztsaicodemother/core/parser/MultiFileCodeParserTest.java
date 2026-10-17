package com.zts.ztsaicodemother.core.parser;

import com.zts.ztsaicodemother.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多文件代码解析器测试，重点覆盖「模型把 CSS/JS 内联进 HTML」时的兜底拆分
 */
class MultiFileCodeParserTest {

    private final MultiFileCodeParser parser = new MultiFileCodeParser();

    @Test
    void parseStandardThreeBlocks() {
        String content = """
                创建完成：
                ```html
                <!DOCTYPE html>
                <html>
                <head>
                    <title>多文件示例</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <h1>欢迎使用</h1>
                    <script src="script.js"></script>
                </body>
                </html>
                ```
                ```css
                h1 { color: blue; }
                ```
                ```javascript
                console.log('页面加载完成');
                ```
                """;
        MultiFileCodeResult result = parser.parseCode(content);
        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
        assertEquals("h1 { color: blue; }", result.getCssCode());
        assertTrue(result.getHtmlCode().contains("style.css"));
        assertTrue(result.getHtmlCode().contains("script.js"));
    }

    @Test
    void splitInlineStyleAndScriptIntoFiles() {
        String content = """
                以下是您需要的页面HTML代码：
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <title>单文件形态</title>
                    <style>
                    body { margin: 0; }
                    h1 { color: red; }
                    </style>
                </head>
                <body>
                    <h1>Hello</h1>
                    <script>
                    console.log('inline');
                    </script>
                </body>
                </html>
                ```
                """;
        MultiFileCodeResult result = parser.parseCode(content);

        // CSS / JS 被抽成独立内容
        assertNotNull(result.getCssCode(), "内联 style 应被抽取为 cssCode");
        assertTrue(result.getCssCode().contains("margin: 0"));
        assertTrue(result.getCssCode().contains("h1 { color: red; }"));
        assertNotNull(result.getJsCode(), "内联 script 应被抽取为 jsCode");
        assertTrue(result.getJsCode().contains("console.log('inline')"));

        // HTML 里不再有内联代码，并补上对两个文件的引用
        assertFalse(result.getHtmlCode().toLowerCase().contains("<style"), "HTML 里不应残留 <style>");
        assertFalse(result.getHtmlCode().toLowerCase().contains("<script>"), "HTML 里不应残留内联 script");
        assertTrue(result.getHtmlCode().contains("href=\"style.css\""));
        assertTrue(result.getHtmlCode().contains("src=\"script.js\""));
    }

    @Test
    void keepJsonLdAndExternalScriptWhenNothingToExtract() {
        // 只有 JSON-LD 数据块 + 外链 script（没有内联 JS、没有 style）：不应凭空造出 css/js
        String content = """
                ```html
                <!DOCTYPE html>
                <html>
                <head>
                    <title>结构化数据</title>
                    <link rel="stylesheet" href="style.css">
                    <script type="application/ld+json">{"@context":"https://schema.org"}</script>
                </head>
                <body>
                    <h1>Hi</h1>
                    <script src="script.js"></script>
                </body>
                </html>
                ```
                """;
        MultiFileCodeResult result = parser.parseCode(content);
        assertNotNull(result.getHtmlCode());
        assertNull(result.getCssCode());
        assertNull(result.getJsCode());
        assertTrue(result.getHtmlCode().contains("application/ld+json"), "JSON-LD 应保留在 HTML 里");
    }

    @Test
    void mergeMultipleInlineBlocks() {
        String content = """
                ```html
                <html>
                <head>
                    <style>a { color: red; }</style>
                    <style>b { color: blue; }</style>
                </head>
                <body>
                    <script>var a = 1;</script>
                    <script>var b = 2;</script>
                </body>
                </html>
                ```
                """;
        MultiFileCodeResult result = parser.parseCode(content);
        assertNotNull(result.getCssCode());
        assertTrue(result.getCssCode().contains("a { color: red; }"));
        assertTrue(result.getCssCode().contains("b { color: blue; }"));
        assertNotNull(result.getJsCode());
        assertTrue(result.getJsCode().contains("var a = 1;"));
        assertTrue(result.getJsCode().contains("var b = 2;"));
        // 只应补一次引用
        assertEquals(1, countOccurrences(result.getHtmlCode(), "style.css"));
        assertEquals(1, countOccurrences(result.getHtmlCode(), "script.js"));
    }

    @Test
    void blankHtmlReturnsEmptyResult() {
        MultiFileCodeResult result = parser.parseCode("模型什么代码都没输出");
        assertNull(result.getHtmlCode());
        assertNull(result.getCssCode());
        assertNull(result.getJsCode());
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(target, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + target.length();
        }
    }
}
