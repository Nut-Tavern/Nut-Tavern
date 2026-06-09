package com.nuttavern.network

import com.nuttavern.data.model.BuiltInTool
import com.nuttavern.data.model.ClaudePromptCacheTtl
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ModelAbility
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * 与 Provider API 交互的网络层。
 *
 * 关键设计:
 * - 入参直接吃 [Provider] / [Model],由它们的字段决定 URL / Header / 请求体;
 * - [Model.providerOverride] 优先于父 [Provider],允许"这一条模型走别的 baseUrl / key";
 * - [Model.abilities] 决定是否传思考量参数(REASONING)和 OpenAI 本地 function calling tools(TOOL);
 * - [Model.inputModalities] 决定后续是否能拼图片消息(本轮纯文本,留 hook);
 * - 错误信息统一走 sanitizer,避免泄露请求里的 host 完整路径。
 *
 * 不在这里做的事:消息历史拼接(让 ViewModel 给 [ChatMessage] 列表)、模型选择、能力推断(走
 * [com.nuttavern.data.registry.ModelRegistry])。
 */
@Singleton
class ChatApiClient @Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun streamChat(
        provider: Provider,
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        thinkingLevel: ThinkingLevel = ThinkingLevel.Auto,
        generationParams: GenerationParams = GenerationParams.Empty,
        tools: List<ChatTool> = emptyList(),
        toolCallRecurseLimit: Int = 0,
    ): Flow<ChatStreamChunk> {
        val effective = effectiveProvider(provider, model)
        // thinkingLevel 是会话级思考量(ChatViewModel.currentThinkingLevel)。预设级覆盖
        // (thinkingLevelOverride)是前向 hook,当前 PromptComposer 恒传 null,故实际走会话级。
        val effectiveThinking = generationParams.thinkingLevelOverride ?: thinkingLevel
        // 工具门控:仅模型声明 TOOL 能力且注册表非空时才参与 function calling。
        // 当前只有 OpenAI chat completions 路径接了本地工具,其余协议忽略 tools。
        val activeTools = if (model.abilities.contains(ModelAbility.TOOL)) tools else emptyList()
        return when (effective) {
            is Provider.OpenAI -> {
                if (effective.useResponsesApi) {
                    streamOpenAIResponses(effective, model, messages, systemPrompt, effectiveThinking, generationParams)
                } else {
                    streamOpenAI(
                        effective, model, messages, systemPrompt, effectiveThinking, generationParams,
                        activeTools, toolCallRecurseLimit,
                    )
                }
            }
            is Provider.Google -> streamGoogle(effective, model, messages, systemPrompt, effectiveThinking, generationParams)
            is Provider.Claude -> streamClaude(effective, model, messages, systemPrompt, effectiveThinking, generationParams)
        }
    }

    /**
     * 拉取该 Provider 远端模型列表。
     *
     * 设计取舍:用 `suspend fun` 而不是 `Flow<Result<...>>` —— 这个调用本质上只发一次请求 / 拿
     * 一次结果,Flow 在调用端必然走 `.first()` 收尾,会触发 AbortFlowException;若再在 `catch`
     * 里 emit 错误就违反 Flow 异常透明性,直接崩主线程。这里直接返回 Result。
     */
    suspend fun fetchModels(provider: Provider): Result<List<String>> {
        val url = when (provider) {
            is Provider.OpenAI -> buildVersionedEndpointUrl(provider.baseUrl, OPENAI_API_VERSION, OPENAI_MODELS_ENDPOINT)
            is Provider.Google -> buildVersionedEndpointUrl(provider.baseUrl, GEMINI_API_VERSION, GEMINI_MODELS_ENDPOINT)
            // Anthropic 在 2024 年加了 GET /v1/models,响应结构与 OpenAI 同形(data[].id);
            // 不再返回 UnsupportedOperationException。
            is Provider.Claude -> buildVersionedEndpointUrl(provider.baseUrl, CLAUDE_API_VERSION, CLAUDE_MODELS_ENDPOINT)
        }

        return try {
            val builder = Request.Builder().url(url)
            when (provider) {
                is Provider.OpenAI -> builder.header("Authorization", "Bearer ${provider.apiKey}")
                is Provider.Google -> builder.header("x-goog-api-key", provider.apiKey)
                is Provider.Claude -> builder
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
            }
            withContext(Dispatchers.IO) {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure<List<String>>(
                            Exception(buildHttpErrorMessage(response, url)),
                        )
                    }
                    val body = response.body?.string()
                        ?: return@withContext Result.failure<List<String>>(Exception("Empty response"))
                    Result.success(parseModelList(provider, body))
                }
            }
        } catch (e: Exception) {
            // 这里**不**再用 Exception 包一层。原始异常带完整 cause chain
            // (UnknownHostException / SocketTimeoutException 等),toSafeErrorMessage 才能
            // 按类型识别;包装会让 cause 在外层串起来,降级回类型名 + message 的通用分支。
            Result.failure(Exception(toSafeErrorMessage(e), e))
        }
    }

    /**
     * 解析"父 Provider + 模型 override"得到本次实际生效的 Provider 配置。
     * Override 不存在时直接返回父 Provider;存在时,override 的 sealed type 与
     * 父类型必须一致,否则忽略 override(异型混用语义不明)。
     */
    private fun effectiveProvider(parent: Provider, model: Model): Provider {
        val override = model.providerOverride ?: return parent
        return when (parent) {
            is Provider.OpenAI -> if (override is Provider.OpenAI) override.mergeOnto(parent) else parent
            is Provider.Google -> if (override is Provider.Google) override.mergeOnto(parent) else parent
            is Provider.Claude -> if (override is Provider.Claude) override.mergeOnto(parent) else parent
        }
    }

    private fun Provider.OpenAI.mergeOnto(parent: Provider.OpenAI): Provider.OpenAI = copy(
        id = parent.id,
        enabled = parent.enabled,
        models = parent.models,
        order = parent.order,
        apiKey = apiKey.ifBlank { parent.apiKey },
        baseUrl = baseUrl.ifBlank { parent.baseUrl },
        chatCompletionsPath = chatCompletionsPath.ifBlank { parent.chatCompletionsPath },
        // useResponsesApi 是 boolean,Override 的取值即"显式配置";没有"空"语义,直接覆盖。
        useResponsesApi = useResponsesApi,
    )

    private fun Provider.Google.mergeOnto(parent: Provider.Google): Provider.Google = copy(
        id = parent.id,
        enabled = parent.enabled,
        models = parent.models,
        order = parent.order,
        apiKey = apiKey.ifBlank { parent.apiKey },
        baseUrl = baseUrl.ifBlank { parent.baseUrl },
    )

    private fun Provider.Claude.mergeOnto(parent: Provider.Claude): Provider.Claude = copy(
        id = parent.id,
        enabled = parent.enabled,
        models = parent.models,
        order = parent.order,
        apiKey = apiKey.ifBlank { parent.apiKey },
        baseUrl = baseUrl.ifBlank { parent.baseUrl },
        // 提示缓存属于 Provider 级配置,不在 EditModelDialog 暴露;Override 必须显式继承
        // parent,否则 override 的默认 false / FIVE_MINUTES 会把父 Provider 启用过的缓存悄悄关掉。
        promptCaching = parent.promptCaching,
        promptCacheTtl = parent.promptCacheTtl,
    )

    // ── OpenAI Chat Completions SSE ──────────────────

    private fun streamOpenAI(
        provider: Provider.OpenAI,
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
        tools: List<ChatTool>,
        toolCallRecurseLimit: Int,
    ): Flow<ChatStreamChunk> = channelFlow {
        val url = buildVersionedEndpointUrl(
            baseUrl = provider.baseUrl,
            apiVersion = OPENAI_API_VERSION,
            endpointPath = provider.chatCompletionsPath.ifBlank { OPENAI_CHAT_COMPLETIONS_ENDPOINT },
        )
        val toolsByName = tools.associateBy { it.name }

        // 工具调用循环:每一轮发一次 SSE 请求。模型返回普通文本 → 结束;返回 tool_calls →
        // 本地执行工具、把 assistant(tool_calls) 与 tool 结果追加进历史,再发下一轮。
        // 轮数受 toolCallRecurseLimit 约束(达到上限后最后一轮不再带 tools,强制模型给文本回复)。
        val conversation = messages.toMutableList()
        var remainingToolRounds = if (tools.isEmpty()) 0 else toolCallRecurseLimit.coerceAtLeast(0)

        while (true) {
            val allowTools = remainingToolRounds > 0
            val body = buildOpenAIRequest(
                model, conversation, systemPrompt, thinkingLevel, generationParams,
                if (allowTools) tools else emptyList(),
            )
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .applyCustomHeaders(model)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val outcome = streamOpenAIRound(request, url, forwardDeltas = true)
            when (outcome) {
                is OpenAIRoundResult.Failed -> {
                    send(ChatStreamChunk(content = "", isDone = true, error = outcome.error))
                    return@channelFlow
                }
                is OpenAIRoundResult.Completed -> {
                    send(ChatStreamChunk(content = "", isDone = true))
                    return@channelFlow
                }
                is OpenAIRoundResult.ToolCalls -> {
                    // 把模型这一轮的 assistant(tool_calls) 原样追加,再逐个执行工具回灌结果。
                    conversation.add(assistantToolCallMessage(outcome.calls))
                    for (call in outcome.calls) {
                        send(ChatStreamChunk(content = "", toolActivity = call.name))
                        val result = executeToolCall(toolsByName, call)
                        conversation.add(toolResultMessage(call.id, result))
                    }
                    remainingToolRounds -= 1
                }
            }
        }
    }

    /** 一轮 OpenAI SSE 的结果。 */
    private sealed interface OpenAIRoundResult {
        /** 模型给出普通文本回复,本轮(以及整个请求)结束。 */
        data object Completed : OpenAIRoundResult
        /** 网络 / 解析错误。 */
        data class Failed(val error: String) : OpenAIRoundResult
        /** 模型要求调用工具,需本地执行后回灌再发下一轮。 */
        data class ToolCalls(val calls: List<OpenAIToolCall>) : OpenAIRoundResult
    }

    /** 模型返回的单个 tool_call(arguments 已按流式分片拼接完成)。 */
    private data class OpenAIToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    )

    /**
     * 发起一轮 OpenAI chat completions SSE 并消费到结束。
     *
     * - [forwardDeltas]=true 时,content / reasoning 增量实时通过 [channelFlow] 发给上层;
     * - 累积 tool_calls 分片(index → id/name/arguments),流结束后判定本轮结果;
     * - 不在这里发 isDone:由调用方根据 [OpenAIRoundResult] 决定是否真正结束。
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatStreamChunk>.streamOpenAIRound(
        request: Request,
        url: String,
        forwardDeltas: Boolean,
    ): OpenAIRoundResult = suspendCancellableCoroutine { continuation ->
        val toolCallBuilders = sortedMapOf<Int, ToolCallBuilder>()
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)

        fun finish(result: OpenAIRoundResult) {
            if (resumed.compareAndSet(false, true)) continuation.resume(result)
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    eventSource.cancel()
                    finish(buildRoundResult(toolCallBuilders))
                    return
                }
                try {
                    val choice = JSONObject(data).getJSONArray("choices").optJSONObject(0)
                    val delta = choice?.optJSONObject("delta")
                    accumulateToolCallDeltas(delta, toolCallBuilders)
                    if (forwardDeltas) {
                        val content = delta?.optCleanString("content").orEmpty()
                        val reasoningContent = parseOpenAIReasoningDelta(delta)
                        if (content.isNotEmpty() || reasoningContent.isNotEmpty()) {
                            trySend(ChatStreamChunk(content = content, reasoningContent = reasoningContent))
                        }
                    }
                } catch (_: Exception) {
                    eventSource.cancel()
                    finish(OpenAIRoundResult.Failed("服务返回了无法解析的响应"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                finish(buildRoundResult(toolCallBuilders))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (resumed.get()) return
                finish(OpenAIRoundResult.Failed(buildNetworkErrorMessage(response, t, url)))
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        continuation.invokeOnCancellation { eventSource.cancel() }
    }

    /** tool_calls 分片累积器(同一 index 的 id/name 只来一次,arguments 分多片拼接)。 */
    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    /** 把一帧 delta 里的 tool_calls 分片并入累积器。 */
    private fun accumulateToolCallDeltas(delta: JSONObject?, builders: MutableMap<Int, ToolCallBuilder>) {
        val toolCalls = delta?.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val entry = toolCalls.optJSONObject(i) ?: continue
            val index = entry.optInt("index", i)
            val builder = builders.getOrPut(index) { ToolCallBuilder() }
            entry.optString("id").takeIf { it.isNotBlank() }?.let { builder.id = it }
            val fn = entry.optJSONObject("function")
            fn?.optString("name")?.takeIf { it.isNotBlank() }?.let { builder.name = it }
            fn?.optString("arguments")?.let { builder.arguments.append(it) }
        }
    }

    /** 流结束:有 tool_calls 走工具分支,否则视为普通文本完成。 */
    private fun buildRoundResult(builders: Map<Int, ToolCallBuilder>): OpenAIRoundResult {
        val calls = builders.values
            .filter { it.name.isNotBlank() }
            .map { OpenAIToolCall(id = it.id, name = it.name, arguments = it.arguments.toString()) }
        return if (calls.isEmpty()) OpenAIRoundResult.Completed else OpenAIRoundResult.ToolCalls(calls)
    }

    /** 执行单个工具调用,失败时返回错误 JSON 回灌给模型(让模型自己决定如何处理)。 */
    private suspend fun executeToolCall(
        toolsByName: Map<String, ChatTool>,
        call: OpenAIToolCall,
    ): String {
        val tool = toolsByName[call.name]
            ?: return JSONObject().put("error", "unknown tool: ${call.name}").toString()
        val arguments = try {
            if (call.arguments.isBlank()) JSONObject() else JSONObject(call.arguments)
        } catch (_: Exception) {
            JSONObject()
        }
        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "tool execution failed").toString()
        }
    }

    // ── OpenAI Responses API SSE ──────────────────────
    //
    // 与 chat/completions 的差异:
    // - 端点 /v1/responses;
    // - body 形态:`instructions`(替代 system message)+ `input`(替代 messages)+
    //   `reasoning: { effort }`(替代 reasoning_effort);
    // - SSE 用具名事件:`response.output_text.delta` / `response.reasoning_summary_text.delta` /
    //   `response.completed` / `response.error`,没有 `[DONE]` 哨兵;
    // - delta 直接挂在事件 JSON 的 `delta` 字段,不是 chat completions 的 choices[0].delta.content。
    //
    // 这条路径只在 provider.useResponsesApi=true 时启用,默认仍走 chat/completions。
    // 端点路径与 chat/completions 路径**独立**:不复用 chatCompletionsPath,统一走默认 "responses"。
    // 用户如果非要改 path,可以走 customHeaders / 中转层。

    private fun streamOpenAIResponses(
        provider: Provider.OpenAI,
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
    ): Flow<ChatStreamChunk> = callbackFlow {
        val jsonBody = buildOpenAIResponsesRequest(model, messages, systemPrompt, thinkingLevel, generationParams)
        val url = buildVersionedEndpointUrl(
            baseUrl = provider.baseUrl,
            apiVersion = OPENAI_API_VERSION,
            endpointPath = OPENAI_RESPONSES_ENDPOINT,
        )

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .applyCustomHeaders(model)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val listener = object : EventSourceListener() {
            private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    when (type) {
                        "response.output_text.delta" -> {
                            val text = JSONObject(data).optCleanString("delta")
                            if (text.isNotEmpty()) {
                                trySend(ChatStreamChunk(content = text))
                            }
                        }
                        "response.reasoning_summary_text.delta",
                        "response.reasoning_text.delta" -> {
                            // GPT-5 / o-series 走 reasoning_summary_text;部分模型直接发 reasoning_text。
                            val reasoning = JSONObject(data).optCleanString("delta")
                            if (reasoning.isNotEmpty()) {
                                trySend(ChatStreamChunk(content = "", reasoningContent = reasoning))
                            }
                        }
                        "response.completed" -> {
                            completed.set(true)
                            trySend(ChatStreamChunk(content = "", isDone = true))
                            eventSource.cancel()
                        }
                        "response.error", "error" -> {
                            completed.set(true)
                            val message = parseProviderErrorMessage(JSONObject(data))
                                .ifBlank { "OpenAI Responses 返回错误,请检查模型、API Key 或权限配置" }
                            trySend(ChatStreamChunk(content = "", isDone = true, error = message))
                            eventSource.cancel()
                        }
                        // 其余事件(response.created / response.in_progress / response.output_item.* /
                        // response.content_part.* / response.output_text.done 等)对 UI 流式渲染无信息,忽略。
                    }
                } catch (_: Exception) {
                    completed.set(true)
                    trySend(ChatStreamChunk(content = "", isDone = true, error = "服务返回了无法解析的响应"))
                    eventSource.cancel()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed.get()) {
                    trySend(ChatStreamChunk(content = "", isDone = true))
                }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed.get()) {
                    close()
                    return
                }
                trySend(ChatStreamChunk(content = "", isDone = true, error = buildNetworkErrorMessage(response, t, url)))
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    // ── Claude Messages SSE ───────────────────────────

    private fun streamClaude(
        provider: Provider.Claude,
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
    ): Flow<ChatStreamChunk> = callbackFlow {
        val jsonBody = buildClaudeRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            thinkingLevel = thinkingLevel,
            promptCaching = provider.promptCaching,
            promptCacheTtl = provider.promptCacheTtl,
            generationParams = generationParams,
        )
        val url = buildVersionedEndpointUrl(
            baseUrl = provider.baseUrl,
            apiVersion = CLAUDE_API_VERSION,
            endpointPath = CLAUDE_MESSAGES_ENDPOINT,
        )

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply {
                // 1h TTL 是 beta 能力,需要显式带 beta header,服务端否则返回 400。
                if (provider.promptCaching && provider.promptCacheTtl == ClaudePromptCacheTtl.ONE_HOUR) {
                    header("anthropic-beta", ANTHROPIC_BETA_EXTENDED_CACHE_TTL)
                }
            }
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .applyCustomHeaders(model)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val listener = object : EventSourceListener() {
            // 与 OpenAI 路径同款"业务完成"标记。详见 streamOpenAI 的注释。
            private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val json = JSONObject(data)
                    val eventType = json.optString("type", "")
                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val text = parseClaudeTextDelta(delta)
                            val reasoningContent = parseClaudeReasoningDelta(delta)
                            if (text.isNotEmpty() || reasoningContent.isNotEmpty()) {
                                trySend(ChatStreamChunk(content = text, reasoningContent = reasoningContent))
                            }
                        }
                        "message_stop" -> {
                            completed.set(true)
                            trySend(ChatStreamChunk(content = "", isDone = true))
                            eventSource.cancel()
                        }
                        "error" -> {
                            completed.set(true)
                            val message = parseProviderErrorMessage(json).ifBlank { "Claude 返回错误,请检查模型、API Key 或权限配置" }
                            trySend(ChatStreamChunk(content = "", isDone = true, error = message))
                            eventSource.cancel()
                        }
                    }
                } catch (_: Exception) {
                    completed.set(true)
                    trySend(ChatStreamChunk(content = "", isDone = true, error = "服务返回了无法解析的响应"))
                    eventSource.cancel()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed.get()) {
                    trySend(ChatStreamChunk(content = "", isDone = true))
                }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed.get()) {
                    close()
                    return
                }
                trySend(ChatStreamChunk(content = "", isDone = true, error = buildNetworkErrorMessage(response, t, url)))
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    // ── Google Gemini SSE ────────────────────────────

    private fun streamGoogle(
        provider: Provider.Google,
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
    ): Flow<ChatStreamChunk> = callbackFlow {
        val jsonBody = buildGeminiRequest(model, messages, systemPrompt, thinkingLevel, generationParams)
        val url = buildGeminiStreamUrl(provider.baseUrl, model.modelId)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", provider.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .applyCustomHeaders(model)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val listener = object : EventSourceListener() {
            // 与 OpenAI 路径同款"业务完成"标记。详见 streamOpenAI 的注释。
            private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val json = JSONObject(data)
                    val errorMessage = parseGeminiErrorMessage(json)
                    if (errorMessage.isNotBlank()) {
                        completed.set(true)
                        trySend(ChatStreamChunk(content = "", isDone = true, error = errorMessage))
                        eventSource.cancel()
                        return
                    }
                    val chunk = parseGeminiChunk(json)
                    if (chunk.content.isNotEmpty() || chunk.reasoningContent.isNotEmpty()) {
                        trySend(chunk)
                    }
                    val finishReason = parseGeminiFinishReason(json)
                    // Gemini 的 finishReason 取值:STOP / SAFETY / MAX_TOKENS / RECITATION /
                    // BLOCKLIST / LANGUAGE / OTHER。任意非空都视作终止信号;非 STOP / SAFETY
                    // 的值要把原因透出给用户,便于排查"答案被截断 / 被安全策略拦截"等场景。
                    if (finishReason.isNotBlank()) {
                        completed.set(true)
                        val errorText = mapGeminiFinishReasonToError(finishReason)
                        trySend(ChatStreamChunk(content = "", isDone = true, error = errorText))
                        eventSource.cancel()
                    }
                } catch (_: Exception) {
                    completed.set(true)
                    trySend(ChatStreamChunk(content = "", isDone = true, error = "服务返回了无法解析的响应"))
                    eventSource.cancel()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed.get()) {
                    trySend(ChatStreamChunk(content = "", isDone = true))
                }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed.get()) {
                    close()
                    return
                }
                trySend(ChatStreamChunk(content = "", isDone = true, error = buildNetworkErrorMessage(response, t, url)))
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    // ── JSON builders ─────────────────────────────────

    private fun buildOpenAIRequest(
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
        tools: List<ChatTool> = emptyList(),
    ): String {
        val jsonMessages = JSONArray()
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            jsonMessages.put(JSONObject().put("role", "system").put("content", it))
        }
        messages.forEach { msg ->
            val role = normalizeOpenAIRole(msg.role)
            if (role.isBlank()) return@forEach
            when {
                // tool 结果消息:OpenAI 要求 role=tool + tool_call_id + content。
                role == "tool" && msg.toolCallId != null -> {
                    jsonMessages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", msg.toolCallId)
                            .put("content", msg.content),
                    )
                }
                // assistant 发起工具调用:content 可为空,但必须带 tool_calls 数组。
                role == "assistant" && msg.toolCalls != null -> {
                    jsonMessages.put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", msg.content.ifBlank { JSONObject.NULL })
                            .put("tool_calls", msg.toolCalls),
                    )
                }
                msg.images.isEmpty() -> {
                    if (msg.content.isNotBlank()) {
                        jsonMessages.put(JSONObject().put("role", role).put("content", msg.content))
                    }
                }
                else -> {
                    // 带图消息:content 用 parts 数组,文本块 + 每张图一个 image_url(data URL)。
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) {
                        parts.put(JSONObject().put("type", "text").put("text", msg.content))
                    }
                    msg.images.forEach { image ->
                        parts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", image.toDataUrl())),
                        )
                    }
                    if (parts.length() > 0) {
                        jsonMessages.put(JSONObject().put("role", role).put("content", parts))
                    }
                }
            }
        }
        val request = JSONObject()
            .put("model", model.modelId)
            .put("messages", jsonMessages)
            .put("stream", generationParams.streamEnabled)

        applyOpenAIGenerationParams(request, generationParams)

        if (model.abilities.contains(ModelAbility.REASONING)) {
            // OpenAI Chat 用 reasoning_effort 字符串档位。会话级 thinkingLevel 经
            // generationParams.thinkingLevelOverride 透传到这里(见 streamChat:effectiveThinking)。
            // 自动档返回 null = 不发送字段。
            ThinkingLevelMapping.toOpenAIEffort(thinkingLevel)?.let {
                request.put("reasoning_effort", it)
            }
        }
        if (tools.isNotEmpty()) {
            request.put("tools", buildOpenAIToolsArray(tools))
        }
        applyCustomBodies(request, model.customBodies)
        return request.toString()
    }

    /** 把内置工具列表转成 OpenAI `tools` 数组(每个 = {type:function, function:{name,description,parameters}})。 */
    private fun buildOpenAIToolsArray(tools: List<ChatTool>): JSONArray {
        val array = JSONArray()
        tools.forEach { tool ->
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parametersSchema),
                    ),
            )
        }
        return array
    }

    /** 构造一条携带 tool_calls 的 assistant 消息(回灌循环内部用,不持久化)。 */
    private fun assistantToolCallMessage(calls: List<OpenAIToolCall>): ChatMessage {
        val toolCalls = JSONArray()
        calls.forEach { call ->
            toolCalls.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", call.name)
                            .put("arguments", call.arguments),
                    ),
            )
        }
        return ChatMessage(role = "assistant", content = "", toolCalls = toolCalls)
    }

    /** 构造一条 tool 结果消息(回灌循环内部用,不持久化)。 */
    private fun toolResultMessage(toolCallId: String, result: String): ChatMessage =
        ChatMessage(role = "tool", content = result, toolCallId = toolCallId)


    /**
     * 把生成参数应用到 OpenAI chat completions 请求体。
     *
     * - null 字段一律不写入,让 OpenAI 用默认值;
     * - logitBias 非空时下发为 `logit_bias: {token: bias}`;
     * - stop 非空时下发为 `stop: [...]`;
     * - verbosity 仅在 GPT-5 系列下发,但当前不做模型识别,直接透传由模型/中转端忽略;
     *   空字符串 / "auto" 不下发(等价于不指定)。
     */
    private fun applyOpenAIGenerationParams(request: JSONObject, params: GenerationParams) {
        params.temperature?.let { request.put("temperature", it) }
        params.maxTokens?.let { request.put("max_tokens", it) }
        params.topP?.let { request.put("top_p", it) }
        params.frequencyPenalty?.let { request.put("frequency_penalty", it) }
        params.presencePenalty?.let { request.put("presence_penalty", it) }
        params.seed?.let { if (it >= 0) request.put("seed", it) }
        params.n?.let { if (it > 1) request.put("n", it) }
        params.verbosityRawValue
            .takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?.let { request.put("verbosity", it) }
        if (params.logitBias.isNotEmpty()) {
            val biasObj = JSONObject()
            params.logitBias.forEach { (k, v) -> biasObj.put(k, v) }
            request.put("logit_bias", biasObj)
        }
        if (params.stop.isNotEmpty()) {
            request.put("stop", JSONArray(params.stop))
        }
    }

    /**
     * Responses API 请求体。
     *
     * 关键映射:
     * - system prompt → `instructions`(顶层字符串字段);
     * - history → `input` 数组,每个元素 `{role, content: [{type:"input_text", text}]}`;
     * - reasoning_effort → `reasoning.effort`;
     * - stream / model 同 chat completions。
     *
     * Responses API 要求 assistant 历史消息的 content 类型是 `output_text`,user/system 是 `input_text`。
     * 这里我们把所有历史按 role 自动分流;空消息跳过。
     */
    private fun buildOpenAIResponsesRequest(
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
    ): String {
        val input = JSONArray()
        messages.forEach { msg ->
            val role = normalizeOpenAIRole(msg.role)
            if (role.isBlank()) return@forEach
            if (role == "assistant" || msg.images.isEmpty()) {
                // assistant 不带图;无图消息走单文本块。
                if (msg.content.isBlank()) return@forEach
                val partType = if (role == "assistant") "output_text" else "input_text"
                val parts = JSONArray().put(
                    JSONObject().put("type", partType).put("text", msg.content),
                )
                input.put(JSONObject().put("role", role).put("content", parts))
            } else {
                // 带图 user 消息:input_text + 每张图一个 input_image(data URL)。
                val parts = JSONArray()
                if (msg.content.isNotBlank()) {
                    parts.put(JSONObject().put("type", "input_text").put("text", msg.content))
                }
                msg.images.forEach { image ->
                    parts.put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", image.toDataUrl()),
                    )
                }
                if (parts.length() > 0) {
                    input.put(JSONObject().put("role", role).put("content", parts))
                }
            }
        }
        val request = JSONObject()
            .put("model", model.modelId)
            .put("input", input)
            .put("stream", generationParams.streamEnabled)
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            request.put("instructions", it)
        }
        // Responses API 使用 generationConfig 风格:max_output_tokens / temperature / top_p 等
        // 直接挂在请求体顶层。
        generationParams.temperature?.let { request.put("temperature", it) }
        generationParams.topP?.let { request.put("top_p", it) }
        generationParams.maxTokens?.let { request.put("max_output_tokens", it) }
        generationParams.seed?.let { if (it >= 0) request.put("seed", it) }
        if (model.abilities.contains(ModelAbility.REASONING)) {
            // Responses API 用 reasoning 对象,字段叫 effort;另外可选 summary,这里不开。
            // 自动档返回 null = 不发送 reasoning。
            ThinkingLevelMapping.toOpenAIEffort(thinkingLevel)?.let {
                request.put("reasoning", JSONObject().put("effort", it))
            }
        }
        applyCustomBodies(request, model.customBodies)
        return request.toString()
    }

    private fun buildClaudeRequest(
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl,
        generationParams: GenerationParams,
    ): String {
        val jsonMessages = JSONArray()
        // 多轮场景下我们只在"最后一条 user 消息"上打 cache_control,这样下一轮请求
        // 把这条消息及之前的全部内容作为缓存前缀复用,符合 Anthropic 推荐的 incremental caching 模式。
        val lastUserIndex = if (promptCaching) messages.indexOfLast { normalizeClaudeRole(it.role) == "user" } else -1
        messages.forEachIndexed { index, msg ->
            val role = normalizeClaudeRole(msg.role)
            if (role.isBlank()) return@forEachIndexed
            val hasContent = msg.content.isNotBlank()
            val hasImages = msg.images.isNotEmpty()
            if (!hasContent && !hasImages) return@forEachIndexed

            val msgObj = JSONObject().put("role", role)
            if (hasImages) {
                // 带图消息:content 用 block 数组,图片块在前、文本块在后(对齐 Anthropic 示例)。
                // cache_control 只打在最后一个文本块(若是缓存目标),与纯文本路径语义一致。
                val blocks = JSONArray()
                msg.images.forEach { image ->
                    blocks.put(buildClaudeImageBlock(image))
                }
                if (hasContent) {
                    blocks.put(
                        if (index == lastUserIndex) {
                            buildClaudeTextBlockWithCache(msg.content, promptCacheTtl)
                        } else {
                            JSONObject().put("type", "text").put("text", msg.content)
                        },
                    )
                }
                msgObj.put("content", blocks)
            } else if (index == lastUserIndex) {
                msgObj.put(
                    "content",
                    JSONArray().put(buildClaudeTextBlockWithCache(msg.content, promptCacheTtl)),
                )
            } else {
                msgObj.put("content", msg.content)
            }
            jsonMessages.put(msgObj)
        }
        val request = JSONObject()
            .put("model", model.modelId)
            .put("messages", jsonMessages)
            // Claude 必填 max_tokens;预设没指定时 fallback 到 4096(原默认值)。
            .put("max_tokens", generationParams.maxTokens ?: 4096)
            .put("stream", generationParams.streamEnabled)

        // Claude 支持 temperature / top_p / top_k / stop_sequences;不支持 frequency / presence penalty。
        generationParams.temperature?.let {
            // Claude 的 temperature 范围 [0, 1],OpenAI 是 [0, 2];预设里若超过 1 会被 Anthropic 拒,
            // 这里钳到 [0, 1] 让用户感知不到差异。
            request.put("temperature", it.coerceIn(0.0, 1.0))
        }
        generationParams.topP?.let { request.put("top_p", it) }
        generationParams.topK?.let { if (it > 0) request.put("top_k", it) }
        if (generationParams.stop.isNotEmpty()) {
            request.put("stop_sequences", JSONArray(generationParams.stop))
        }
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            if (promptCaching) {
                // system 字符串模式不支持 cache_control,启用缓存时一律转成数组形式。
                request.put("system", JSONArray().put(buildClaudeTextBlockWithCache(it, promptCacheTtl)))
            } else {
                request.put("system", it)
            }
        }
        if (model.abilities.contains(ModelAbility.REASONING)) {
            // Claude extended thinking: {type:"enabled", budget_tokens:N}。budget 钳到
            // [1024, max_tokens-1];关闭 / 自动返回 null = 不发送 thinking 字段。
            ThinkingLevelMapping.toClaudeThinking(thinkingLevel, generationParams.maxTokens)?.let {
                request.put("thinking", it)
            }
        }
        applyCustomBodies(request, model.customBodies)
        return request.toString()
    }

    /**
     * 构造 `{"type":"text","text":...,"cache_control":{"type":"ephemeral"[,"ttl":"1h"]}}`。
     *
     * Anthropic prompt caching:
     * - 默认 TTL 5 分钟,API 请求里**不带** ttl 字段;
     * - 1h TTL 需要在 ttl 里显式写 "1h",并在请求 header 上加 `anthropic-beta: extended-cache-ttl-2025-04-11`。
     */
    private fun buildClaudeTextBlockWithCache(text: String, ttl: ClaudePromptCacheTtl): JSONObject {
        val cacheControl = JSONObject().put("type", "ephemeral")
        ttl.apiValue?.let { cacheControl.put("ttl", it) }
        return JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("cache_control", cacheControl)
    }

    /** Claude 图片块:`{type:"image", source:{type:"base64", media_type, data}}`。 */
    private fun buildClaudeImageBlock(image: ChatImage): JSONObject {
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", image.mimeType)
            .put("data", image.base64Data)
        return JSONObject()
            .put("type", "image")
            .put("source", source)
    }

    private fun buildGeminiRequest(
        model: Model,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        thinkingLevel: ThinkingLevel,
        generationParams: GenerationParams,
    ): String {
        val contents = JSONArray()
        for (msg in messages) {
            val role = normalizeGeminiRole(msg.role)
            if (role.isBlank()) continue
            val hasContent = msg.content.isNotBlank()
            val hasImages = msg.images.isNotEmpty()
            if (!hasContent && !hasImages) continue

            val parts = JSONArray()
            if (hasContent) {
                parts.put(JSONObject().put("text", msg.content))
            }
            // Gemini REST 用 snake_case:inline_data.mime_type / data(裸 base64)。
            msg.images.forEach { image ->
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", image.mimeType)
                            .put("data", image.base64Data),
                    ),
                )
            }
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }

        val requestBody = JSONObject().put("contents", contents)
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            val systemInstruction = JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", it)))
            requestBody.put("systemInstruction", systemInstruction)
        }

        // Gemini 把生成参数挂在 generationConfig 子对象里。
        val generationConfig = requestBody.optJSONObject("generationConfig") ?: JSONObject()
        generationParams.temperature?.let { generationConfig.put("temperature", it) }
        generationParams.maxTokens?.let { generationConfig.put("maxOutputTokens", it) }
        generationParams.topP?.let { generationConfig.put("topP", it) }
        generationParams.topK?.let { if (it > 0) generationConfig.put("topK", it) }
        generationParams.seed?.let { if (it >= 0) generationConfig.put("seed", it) }
        if (generationParams.stop.isNotEmpty()) {
            generationConfig.put("stopSequences", JSONArray(generationParams.stop))
        }

        if (model.abilities.contains(ModelAbility.REASONING)) {
            // Gemini thinkingConfig:Effort 档发 thinkingLevel 字符串,关闭 / 自定义发
            // thinkingBudget 整数;自动返回 null = 不发送 thinkingConfig。
            ThinkingLevelMapping.toGeminiThinkingConfig(thinkingLevel)?.let {
                generationConfig.put("thinkingConfig", it)
            }
        }
        if (generationConfig.length() > 0) {
            requestBody.put("generationConfig", generationConfig)
        }

        // 内置工具:googleSearch / urlContext 走 tools 数组,imageGeneration 通过
        // generationConfig.responseModalities 表达"也允许图像输出"。
        // 三者都需要 Gemini 2.0+,旧模型会被服务端拒,这是用户配置层面的事,这里不做版本判断。
        val builtInTools = model.builtInTools
        if (BuiltInTool.Search in builtInTools || BuiltInTool.UrlContext in builtInTools) {
            val toolEntry = JSONObject()
            if (BuiltInTool.Search in builtInTools) {
                toolEntry.put("googleSearch", JSONObject())
            }
            if (BuiltInTool.UrlContext in builtInTools) {
                toolEntry.put("urlContext", JSONObject())
            }
            requestBody.put("tools", JSONArray().put(toolEntry))
        }
        if (BuiltInTool.ImageGeneration in builtInTools) {
            val gc = requestBody.optJSONObject("generationConfig") ?: JSONObject()
            gc.put(
                "responseModalities",
                JSONArray().put("TEXT").put("IMAGE"),
            )
            requestBody.put("generationConfig", gc)
        }
        applyCustomBodies(requestBody, model.customBodies)
        return requestBody.toString()
    }

    /**
     * 把模型自定义 body 字段并入请求体。
     *
     * - [CustomBody.key] 空则跳过整条;
     * - [CustomBody.jsonValue] 先尝试用 JSONTokener 解析(支持对象 / 数组 / 数字 / 布尔 / null),
     *   解析失败按字符串字面量透传;
     * - 同名 key 后写覆盖先写,允许用户故意覆盖默认字段(如 max_tokens)。
     */
    /**
     * 应用模型自定义 body 字段。后写覆盖先写,失败按字符串透传。
     *
     * 关键字段保护:`stream` / `messages` / `input` / `contents` / `model` 是 SSE 流式契约的
     * 核心字段,被覆盖会让请求行为不可预期(关闭 stream → SSE 端永远空转;覆盖 messages → 历史
     * 全丢)。这些 key 直接跳过,不让用户在 EditModelDialog 里误改造成排查灾难。
     */
    private fun applyCustomBodies(json: JSONObject, bodies: List<com.nuttavern.data.model.CustomBody>) {
        for (entry in bodies) {
            val key = entry.key.trim()
            if (key.isBlank()) continue
            if (key in PROTECTED_BODY_KEYS) continue
            val raw = entry.jsonValue
            val parsed = try {
                org.json.JSONTokener(raw).nextValue()
            } catch (_: Exception) {
                raw
            }
            json.put(key, parsed)
        }
    }

    /**
     * 把模型自定义 headers 应用到 OkHttp request builder 上。同名 header 后写覆盖先写。
     * 用户可能覆盖 Authorization / x-api-key 等默认 header,这是有意行为(适配中转)。
     */
    private fun Request.Builder.applyCustomHeaders(model: Model): Request.Builder {
        for (h in model.customHeaders) {
            val name = h.name.trim()
            if (name.isBlank()) continue
            this.header(name, h.value)
        }
        return this
    }

    /**
     * 把不同协议的 list models 响应解析成 modelId 列表。
     *
     * 解析失败抛 [IllegalStateException] 而不是兜底空列表:中转返回 HTML 登录页 / 旧 schema /
     * 字段缺失等场景如果静默成 emptyList(),用户在 UI 上只能看到"远端 0 个模型"且没有任何
     * 排查线索。让外层 fetchModels 捕获后转 Result.failure,UI 走 RemoteModelsState.Failed
     * 才能给用户"重试 / 检查 endpoint"的提示。
     */
    private fun parseModelList(provider: Provider, body: String): List<String> {
        try {
            val json = JSONObject(body)
            return when (provider) {
                is Provider.OpenAI -> {
                    val data = json.getJSONArray("data")
                    (0 until data.length()).map { data.getJSONObject(it).getString("id") }
                }
                is Provider.Google -> {
                    val models = json.getJSONArray("models")
                    (0 until models.length()).mapNotNull { i ->
                        val m = models.getJSONObject(i)
                        val name = m.optString("name", "")
                        val supportedMethods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        var supportsGenerate = false
                        for (j in 0 until supportedMethods.length()) {
                            if (supportedMethods.getString(j) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                        if (supportsGenerate && name.isNotBlank()) name.removePrefix("models/") else null
                    }
                }
                is Provider.Claude -> {
                    // Anthropic /v1/models 响应同 OpenAI:{"data": [{"id": "..."}]}
                    val data = json.getJSONArray("data")
                    (0 until data.length()).map { data.getJSONObject(it).getString("id") }
                }
            }
        } catch (e: Exception) {
            // 把解析失败暴露出去:常见原因是中转/代理返回了非 JSON(HTML 登录页 / 错误纯文本)
            // 或字段命名变化。包一层带 cause,toSafeErrorMessage 仍可识别根因类型。
            throw IllegalStateException("解析模型列表失败,响应可能不是预期的 JSON 结构", e)
        }
    }

    // ── URL & misc helpers ────────────────────────────

    private fun buildVersionedEndpointUrl(
        baseUrl: String,
        apiVersion: String,
        endpointPath: String,
    ): String {
        val endpointSegments = normalizeEndpointPathSegments(endpointPath, apiVersion)
        val builder = buildVersionRootUrlBuilder(baseUrl, apiVersion)
        endpointSegments.forEach { segment -> builder.addPathSegment(segment) }
        return builder.build().toString()
    }

    private fun buildVersionRootUrlBuilder(
        baseUrl: String,
        apiVersion: String,
    ): okhttp3.HttpUrl.Builder {
        val base = baseUrl.removeSuffix("/").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的服务地址")
        val basePathSegments = base.pathSegments
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val versionIndex = basePathSegments.indexOfLast { it.equals(apiVersion, ignoreCase = true) }
        val rootSegments = if (versionIndex >= 0) {
            basePathSegments.take(versionIndex + 1)
        } else {
            basePathSegments + apiVersion
        }

        val builder = base.newBuilder()
            .query(null)
            .fragment(null)
            .encodedPath("/")
        rootSegments.forEach { segment -> builder.addPathSegment(segment) }
        return builder
    }

    private fun normalizeEndpointPathSegments(endpointPath: String, apiVersion: String): List<String> {
        val requestSegments = endpointPath
            .trim()
            .trimStart('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (requestSegments.isEmpty()) return emptyList()
        return if (requestSegments.first().equals(apiVersion, ignoreCase = true)) {
            requestSegments.drop(1)
        } else {
            requestSegments
        }
    }

    private fun buildGeminiStreamUrl(baseUrl: String, modelId: String): String {
        val name = modelId.trim().removePrefix("models/")
        if (name.isBlank()) throw IllegalArgumentException("请先选择 Gemini 模型")
        val builder = buildVersionRootUrlBuilder(baseUrl, GEMINI_API_VERSION)
        builder.addPathSegment("models")
        return builder
            .addEncodedPathSegment("$name:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()
            .toString()
    }

    private fun normalizeOpenAIRole(role: String): String =
        when (role.trim().lowercase()) {
            "system", "user", "assistant", "tool" -> role.trim().lowercase()
            else -> "user"
        }

    private fun normalizeClaudeRole(role: String): String =
        when (role.trim().lowercase()) {
            "assistant" -> "assistant"
            "user", "system" -> "user"
            else -> "user"
        }

    private fun normalizeGeminiRole(role: String): String =
        when (role.trim().lowercase()) {
            "assistant", "model" -> "model"
            "user", "system" -> "user"
            else -> "user"
        }

    private fun parseGeminiChunk(json: JSONObject): ChatStreamChunk {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ChatStreamChunk(content = "")
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.optCleanString("text")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                reasoningBuilder.append(text)
            } else {
                contentBuilder.append(text)
            }
        }
        return ChatStreamChunk(
            content = contentBuilder.toString(),
            reasoningContent = reasoningBuilder.toString(),
        )
    }

    private fun parseGeminiFinishReason(json: JSONObject): String =
        json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("finishReason", "")
            .orEmpty()

    /**
     * 把 Gemini finishReason 映射成给用户的错误文案;返回空字符串表示"正常完成,不报错"。
     * STOP / SAFETY 视为正常,其他值都需要透出。
     */
    private fun mapGeminiFinishReasonToError(reason: String): String? = when (reason) {
        "STOP", "SAFETY" -> null
        "MAX_TOKENS" -> "回复因达到最大输出长度被截断,可在模型设置里调高 maxOutputTokens"
        "RECITATION" -> "回复因引用检测被拦截"
        "BLOCKLIST" -> "回复触发了 Gemini 词表黑名单"
        "LANGUAGE" -> "回复因语言策略被拦截"
        "OTHER" -> "Gemini 终止了本次回复(原因未明确)"
        else -> "Gemini 异常终止: $reason"
    }

    private fun parseGeminiErrorMessage(json: JSONObject): String {
        val error = json.optJSONObject("error") ?: return ""
        val message = error.optString("message", "").take(SAFE_ERROR_BODY_LIMIT)
        val status = error.optString("status", "")
        return when {
            status.isNotBlank() && message.isNotBlank() -> "Gemini $status: $message"
            message.isNotBlank() -> "Gemini error: $message"
            else -> "Gemini 返回错误,请检查模型、API Key 或权限配置"
        }
    }

    private fun parseProviderErrorMessage(json: JSONObject): String {
        val error = json.optJSONObject("error") ?: return ""
        val type = error.optString("type", "")
        val message = error.optString("message", "").take(SAFE_ERROR_BODY_LIMIT)
        return when {
            type.isNotBlank() && message.isNotBlank() -> "$type: $message"
            message.isNotBlank() -> message
            else -> ""
        }
    }

    private fun parseOpenAIReasoningDelta(delta: JSONObject?): String {
        if (delta == null) return ""
        val fields = listOf("reasoning_content", "reasoning", "reasoningContent", "thinking", "thinking_content")
        return fields.joinToString(separator = "") { delta.optCleanString(it) }
    }

    private fun parseClaudeTextDelta(delta: JSONObject?): String {
        if (delta == null) return ""
        val deltaType = delta.optCleanString("type")
        if (deltaType.isNotBlank() && deltaType != "text_delta") return ""
        return delta.optCleanString("text")
    }

    private fun parseClaudeReasoningDelta(delta: JSONObject?): String {
        if (delta == null) return ""
        val deltaType = delta.optCleanString("type")
        if (deltaType.isNotBlank() && deltaType != "thinking_delta") return ""
        return delta.optCleanString("thinking")
            .ifBlank { delta.optCleanString("reasoning") }
            .ifBlank { delta.optCleanString("reasoning_content") }
    }

    private fun JSONObject.optCleanString(name: String): String {
        if (!has(name) || isNull(name)) return ""
        val value = optString(name, "")
        return GeneratedContentSanitizer.sanitizeProviderTextField(value)
    }

    private fun buildNetworkErrorMessage(response: Response?, throwable: Throwable?, requestUrl: String): String {
        if (response != null) return buildHttpErrorMessage(response, requestUrl)
        val target = toSafeRequestTarget(requestUrl)
        return "$target 请求失败: ${toSafeErrorMessage(throwable)}"
    }

    private fun buildHttpErrorMessage(response: Response, requestUrl: String): String {
        val target = toSafeRequestTarget(requestUrl)
        val summary = response.body?.string()
            ?.replace(Regex("\\s+"), " ")
            ?.take(SAFE_ERROR_BODY_LIMIT)
            .orEmpty()
        return if (summary.isBlank()) "$target HTTP ${response.code}" else "$target HTTP ${response.code}: $summary"
    }

    private fun toSafeRequestTarget(requestUrl: String): String {
        val url = requestUrl.toHttpUrlOrNull() ?: return "API"
        return "${url.host}${url.encodedPath}"
    }

    private fun toSafeErrorMessage(throwable: Throwable?): String {
        if (throwable == null) return "请求失败"
        // 优先按异常类型给可读文案,再降级到通用 message。这里覆盖测试连接 + 流式请求最常见
        // 的几类失败:DNS / 连接超时 / TLS / 非法 URL,其余仍用类型名 + message 兜底。
        val rootCause = rootCauseOf(throwable)
        return when (rootCause) {
            is UnknownHostException -> {
                val host = rootCause.message?.substringAfter("host \"")?.substringBefore('"').orEmpty()
                if (host.isNotBlank()) "无法解析主机 \"$host\",请检查网络或 Base URL"
                else "无法解析主机,请检查网络或 Base URL"
            }
            is SocketTimeoutException -> "连接超时,请检查网络或对应代理是否可达"
            is SSLException -> "TLS 握手失败:${shortMessage(rootCause)}"
            is IllegalArgumentException -> rootCause.message ?: "请求参数无效"
            else -> {
                val errorType = rootCause::class.simpleName ?: "请求失败"
                val detail = shortMessage(rootCause)
                if (detail.isBlank()) errorType else "$errorType: $detail"
            }
        }
    }

    private fun rootCauseOf(throwable: Throwable): Throwable {
        var current: Throwable = throwable
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun shortMessage(throwable: Throwable): String =
        throwable.message
            ?.replace(Regex("\\s+"), " ")
            ?.take(SAFE_ERROR_BODY_LIMIT)
            .orEmpty()

    @Suppress("unused")
    private fun ignoreImageInput(model: Model): Boolean {
        // 占位:本轮纯文本路径,后续接入图片消息时由 inputModalities.IMAGE 决定是否拼图。
        return Modality.IMAGE !in model.inputModalities
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val OPENAI_API_VERSION = "v1"
        private const val OPENAI_MODELS_ENDPOINT = "models"
        private const val OPENAI_CHAT_COMPLETIONS_ENDPOINT = "chat/completions"
        private const val OPENAI_RESPONSES_ENDPOINT = "responses"
        private const val CLAUDE_API_VERSION = "v1"
        private const val CLAUDE_MESSAGES_ENDPOINT = "messages"
        private const val CLAUDE_MODELS_ENDPOINT = "models"
        // Anthropic API version 头,稳定字段,不会因模型升级变动。
        private const val ANTHROPIC_VERSION = "2023-06-01"
        // 1h prompt cache TTL 的 beta 标识,2025-04-11 GA 之前固定要带这个 header。
        private const val ANTHROPIC_BETA_EXTENDED_CACHE_TTL = "extended-cache-ttl-2025-04-11"
        private const val GEMINI_API_VERSION = "v1beta"
        private const val GEMINI_MODELS_ENDPOINT = "models"
        private const val SAFE_ERROR_BODY_LIMIT = 240

        /**
         * applyCustomBodies 拒绝覆盖的 key 集合。
         *
         * 这些字段被改写后会破坏 SSE 流式契约或丢失历史消息:
         * - `stream`:关掉后 OkHttp SSE 永远空转直到超时;
         * - `messages` / `input` / `contents`:三协议的历史消息容器,被覆盖等于"无历史";
         * - `model`:模型 id 必须由 Model.modelId 决定,运行期改成别的会和 UI 选择脱节。
         */
        private val PROTECTED_BODY_KEYS = setOf("stream", "messages", "input", "contents", "model")
    }
}
