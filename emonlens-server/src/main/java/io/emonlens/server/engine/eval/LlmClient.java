package io.emonlens.server.engine.eval;

import io.emonlens.server.config.SysSettingService;

/**
 * LLM 调用抽象（OpenAI /chat/completions 与 Anthropic /messages 两种协议由实现按配置选择）。
 * 实现不可用时抛 LlmUnavailableException，
 * 由评估层转为 EVAL_UNAVAILABLE 结果（设计文档红线：不得当作 PASS）。
 */
public interface LlmClient {

    /** 当前生效的端点配置（用于未显式指定模型时回退到系统默认）；测试 fake 返回 null。 */
    default SysSettingService.EvalLlm effectiveConfig() {
        return null;
    }

    String chat(String systemPrompt, String userPrompt, String model, double temperature)
            throws LlmUnavailableException;

    /** 带 usage 的评审调用：OpenAI 兼容端点返回 token 消耗；默认实现退化为基础 chat。 */
    default ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model, double temperature)
            throws LlmUnavailableException {
        return new ChatReply(chat(systemPrompt, userPrompt, model, temperature), null, null);
    }

    /**
     * R3-4：带图片的多模态评审。默认实现退化为纯文本调用（fake/旧端点不需要感知图片）；
     * 真正支持 vision 的端点覆写它，把图片作为 content parts 发给模型。
     */
    default ChatReply chatWithImages(String systemPrompt, String userPrompt, java.util.List<String> imageUrls,
                                     String model, double temperature) throws LlmUnavailableException {
        return chatWithUsage(systemPrompt, userPrompt, model, temperature);
    }

    /** 评审回复：content + 可选 token 消耗（Java 11 无 record，用不可变类） */
    final class ChatReply {
        public final String content;
        public final Integer promptTokens;
        public final Integer completionTokens;

        public ChatReply(String content, Integer promptTokens, Integer completionTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public String content() {
            return content;
        }

        public Integer promptTokens() {
            return promptTokens;
        }

        public Integer completionTokens() {
            return completionTokens;
        }
    }
}
