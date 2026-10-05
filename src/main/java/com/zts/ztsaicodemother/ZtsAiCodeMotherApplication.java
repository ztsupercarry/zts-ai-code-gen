package com.zts.ztsaicodemother;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.zts.ztsaicodemother.mapper")
public class ZtsAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZtsAiCodeMotherApplication.class, args);
    }

}
