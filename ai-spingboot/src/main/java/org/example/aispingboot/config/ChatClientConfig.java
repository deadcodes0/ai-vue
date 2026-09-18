package org.example.aispingboot.config;

import org.example.aispingboot.AiService.PromptManage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(30) // 保留最新30条消息
                .build();
    }

    @Bean("open-ai")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory()).build())
                .defaultSystem(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT).build();
    }

    /**
     * 情绪分析专用 ChatClient：不挂 ChatMemory advisor（分析上下文直接拼入 prompt，
     * 避免污染对话记忆、避免依赖记忆重建）
     */
    @Bean("emotion-analysis")
    public ChatClient emotionAnalysisChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }
}
