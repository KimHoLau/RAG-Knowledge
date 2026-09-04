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

import java.time.Duration;
import java.util.List;

/**
 * 大模型接入配置：
 * - 对话模型 GLM-5.3：走智谱开放平台 OpenAI 兼容端点（baseUrl 以 /api/paas/v4 结尾）
 * - 向量模型 qwen3-embedding:4b：走本地 Ollama 的 OpenAI 兼容 /embeddings 服务（baseUrl 以 /v1 结尾）
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
            @Value("${rag.llm.temperature}") double temperature,
            @Value("${rag.llm.timeout-seconds}") long timeoutSeconds,
            @Value("${rag.llm.max-retries}") int maxRetries) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();
        optionsBuilder.model(model);
        optionsBuilder.temperature(temperature);
        // 同时提供同步/异步客户端，异步客户端用于流式输出
        // 显式设置超时与重试：SDK 默认值不适合长回答流式场景，未设置时异常请求会长时间挂住线程
        return OpenAiChatModel.builder()
                .openAiClient(OpenAIOkHttpClient.builder()
                        .baseUrl(baseUrl).apiKey(apiKey).timeout(timeout).maxRetries(maxRetries).build())
                .openAiClientAsync(OpenAIOkHttpClientAsync.builder()
                        .baseUrl(baseUrl).apiKey(apiKey).timeout(timeout).maxRetries(maxRetries).build())
                .options(optionsBuilder.build())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${rag.embedding.base-url}") String baseUrl,
            @Value("${rag.embedding.api-key}") String apiKey,
            @Value("${rag.embedding.model}") String model,
            @Value("${rag.embedding.dimensions}") int dimensions,
            @Value("${rag.embedding.timeout-seconds}") long timeoutSeconds,
            @Value("${rag.embedding.max-retries}") int maxRetries) {
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder();
        optionsBuilder.model(model);
        if (dimensions > 0) {
            optionsBuilder.dimensions(dimensions);
        }
        // 入库一次要向量化成百上千个切片，超时与重试按批量场景单独设置
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
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
        // 用 builder 而非全参构造器：后者在 Spring AI 2.0 已标记过时并将被移除
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSize)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .withPunctuationMarks(List.of('\n', ' ', '。', '，', '；', '、'))
                .build();
    }
}
