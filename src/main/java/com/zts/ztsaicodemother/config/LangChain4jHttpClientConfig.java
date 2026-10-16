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
 * 如果流式模型也出现同样症状，可以照样覆盖 {@code openAiStreamingChatModelHttpClientBuilder}。
 */
@Configuration
public class LangChain4jHttpClientConfig {

    @Bean("openAiChatModelHttpClientBuilder")
    public HttpClientBuilder openAiChatModelHttpClientBuilder() {
        return JdkHttpClient.builder();
    }
}
