package com.zts.ztsaicodemother.config;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 覆盖 langchain4j-open-ai-spring-boot-starter 默认的 HttpClientBuilder。
 *
 * <p>starter 的 AutoConfig 默认给 openAiChatModel 注入 SpringRestClient（底层是 Apache HttpClient 5），
 * 它会发出 {@code Accept-Encoding: gzip, x-gzip, deflate, br}；DeepSeek 的 EdgeOne 因此用 Brotli
 * （{@code content-encoding: br}）返回，而这条解压链路只把响应体的第一个字节 {@code {} 交给 langchain4j，
 * 于是 {@code Json.fromJson} 抛 JsonEOFException。
 *
 * <p>bean 名必须叫 {@code openAiChatModelHttpClientBuilder}：starter 里同名 bean 的注解是
 * {@code @ConditionalOnMissingBean(name = "openAiChatModelHttpClientBuilder")}，我们提供同名 bean 后
 * starter 那个 SpringRestClient 的 bean 就不会再创建，autoconfigured 的 openAiChatModel 会拿到这里这个。
 *
 * <p>JDK HttpClient 不主动声明 br/deflate，DeepSeek 直接返回明文 JSON；它自带完整的 SSE 实现，
 * 所以流式模型（openAiStreamingChatModel）同样覆盖成 JDK 客户端：
 * 既避免 SSE 响应被 Brotli 截断，也能让上游的错误响应体（比如 400 的 JSON）完整地出现在异常里。
 */
@Configuration
public class LangChain4jHttpClientConfig {

    @Bean("openAiChatModelHttpClientBuilder")
    public HttpClientBuilder openAiChatModelHttpClientBuilder() {
        return JdkHttpClient.builder();
    }

    /**
     * 覆盖 starter 给 openAiStreamingChatModel 注入的 SpringRestClient。
     * bean 名必须与 starter 里那个一致（@ConditionalOnMissingBean(name = ...) 才会让位）。
     */
    @Bean("openAiStreamingChatModelHttpClientBuilder")
    public HttpClientBuilder openAiStreamingChatModelHttpClientBuilder() {
        return JdkHttpClient.builder();
    }
}
