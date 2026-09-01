package com.ragknowledge.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 大模型接入配置：
 * - 对话模型 GLM-5.3：走智谱开放平台 OpenAI 兼容端点（baseUrl 以 /api/paas/v4 结尾）
 * - 向量模型 bge-m3：走独立部署的 OpenAI 兼容 /embeddings 服务（baseUrl 以 /v1 结尾，如 Ollama）
 * 两个模型使用不同的 baseUrl，因此不使用 openai starter 的单一连接自动装配，而是手工构建两套客户端。
 * Spring AI 2.0 的 OpenAI 模块包装官方 OpenAI Java SDK，客户端通过 OpenAIOkHttpClient 构建。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatModel chatModel(
            @Value("${rag.llm.base-url}") String baseUrl,
            @Value("${rag.llm.api-key}") String apiKey,
            @Value("${rag.llm.model}") String model,
            @Value("${rag.llm.temperature}") double temperature) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();
        optionsBuilder.model(model);
        optionsBuilder.temperature(temperature);
        // 同时提供同步/异步客户端，异步客户端用于流式输出
        return OpenAiChatModel.builder()
                .openAiClient(OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .openAiClientAsync(OpenAIOkHttpClientAsync.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .options(optionsBuilder.build())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${rag.embedding.base-url}") String baseUrl,
            @Value("${rag.embedding.api-key}") String apiKey,
            @Value("${rag.embedding.model}") String model,
            @Value("${rag.embedding.dimensions}") int dimensions) {
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder();
        optionsBuilder.model(model);
        if (dimensions > 0) {
            optionsBuilder.dimensions(dimensions);
        }
        OpenAIClient client = OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        return OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .options(optionsBuilder.build())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter(
            @Value("${rag.split.chunk-size}") int chunkSize,
            @Value("${rag.split.min-chunk-size}") int minChunkSize) {
        // 分隔符在通用空白符之外补充中文标点，改善中文文档的切分边界
        return new TokenTextSplitter(chunkSize, minChunkSize, 5, 10000, true,
                List.of('\n', ' ', '。', '，', '；', '、'));
    }
}
