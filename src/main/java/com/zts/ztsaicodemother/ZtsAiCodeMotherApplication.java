package com.zts.ztsaicodemother;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.zts.ztsaicodemother.mapper")
public class ZtsAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZtsAiCodeMotherApplication.class, args);
    }

}
