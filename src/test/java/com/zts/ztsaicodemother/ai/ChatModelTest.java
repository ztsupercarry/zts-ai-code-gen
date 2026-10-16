package com.zts.ztsaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest

public class ChatModelTest {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    @Test
    void testChatModel() {
        String result = chatModel.chat("你好");
        Assertions.assertNotNull(result);
    }
}
