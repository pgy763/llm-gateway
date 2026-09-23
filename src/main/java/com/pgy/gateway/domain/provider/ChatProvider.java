package com.pgy.gateway.domain.provider;

import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import com.pgy.gateway.domain.chat.TokenUsage;

import java.util.function.Consumer;

/**
 * 「提供方适配器」接口 —— 整个项目最重要的一个抽象。
 *
 * 为什么需要它？
 * 每家模型厂商的接口细节都不一样（URL 不同、字段名不同、错误码不同、
 * 流式数据的分片格式也不同）。如果业务代码直接去调这些接口，那么
 * "换一家厂商"就意味着"改一堆业务代码"。
 *
 * 有了这个接口，新增一家厂商只需要写一个新的实现类，
 * 业务代码一行都不用动。
 */
public interface ChatProvider {

    /** 提供方名字，例如 deepseek、kimi、mock */
    String name();

    /** 这家提供方是否支持某个模型名 */
    boolean supports(String model);

    /**
     * 非流式调用：等模型全部生成完，一次性返回。
     */
    ChatResponse chat(ChatRequest request, String model);

    /**
     * 流式调用：模型每生成一小段就通过 onDelta 回调吐出来。
     * 网关收到后立即转发给客户端，用户就能看到"打字机"效果。
     *
     * @param onDelta 每收到一小段文本就调用一次
     * @return 本次调用的用量统计（流式场景下用量通常在最后一段才返回）
     */
    TokenUsage chatStream(ChatRequest request, String model, Consumer<String> onDelta);
}
