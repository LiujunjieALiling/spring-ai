/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vertexai.gemini;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ChatModelDescription;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.AbstractToolCallSupport;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.vertexai.gemini.metadata.VertexAiChatResponseMetadata;
import org.springframework.ai.vertexai.gemini.metadata.VertexAiUsage;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.FunctionCall;
import com.google.cloud.vertexai.api.FunctionDeclaration;
import com.google.cloud.vertexai.api.FunctionResponse;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Tool;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseStream;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Christian Tzolov
 * @author Grogdunn
 * @author luocongqiu
 * @since 0.8.1
 */
public class VertexAiGeminiChatModel extends AbstractToolCallSupport<GenerateContentResponse>
		implements ChatModel, DisposableBean {

	private final static boolean IS_RUNTIME_CALL = true;

	private final VertexAI vertexAI;

	private final VertexAiGeminiChatOptions defaultOptions;

	private final GenerationConfig generationConfig;

	public enum GeminiMessageType {

		USER("user"),

		MODEL("model");

		GeminiMessageType(String value) {
			this.value = value;
		}

		public final String value;

		public String getValue() {
			return this.value;
		}

	}

	public enum ChatModel implements ChatModelDescription {

		GEMINI_PRO_VISION("gemini-pro-vision"),

		GEMINI_PRO("gemini-pro"),

		GEMINI_1_5_PRO("gemini-1.5-pro-001"),

		GEMINI_1_5_FLASH("gemini-1.5-flash-001");

		ChatModel(String value) {
			this.value = value;
		}

		public final String value;

		public String getValue() {
			return this.value;
		}

		@Override
		public String getName() {
			return this.value;
		}

	}

	public VertexAiGeminiChatModel(VertexAI vertexAI) {
		this(vertexAI,
				VertexAiGeminiChatOptions.builder()
					.withModel(ChatModel.GEMINI_PRO_VISION)
					.withTemperature(0.8f)
					.build());
	}

	public VertexAiGeminiChatModel(VertexAI vertexAI, VertexAiGeminiChatOptions options) {
		this(vertexAI, options, null);
	}

	public VertexAiGeminiChatModel(VertexAI vertexAI, VertexAiGeminiChatOptions options,
			FunctionCallbackContext functionCallbackContext) {

		super(functionCallbackContext);

		Assert.notNull(vertexAI, "VertexAI must not be null");
		Assert.notNull(options, "VertexAiGeminiChatOptions must not be null");
		Assert.notNull(options.getModel(), "VertexAiGeminiChatOptions.modelName must not be null");

		this.vertexAI = vertexAI;
		this.defaultOptions = options;
		this.generationConfig = toGenerationConfig(options);
	}

	// https://cloud.google.com/vertex-ai/docs/generative-ai/model-reference/gemini
	@Override
	public ChatResponse call(Prompt prompt) {

		var geminiRequest = createGeminiRequest(prompt);

		GenerateContentResponse response = this.getContentResponse(geminiRequest);

		// GenerateContentResponse response = this.callWithFunctionSupport(geminiRequest);

		if (this.isToolFunctionCall(response)) {
			List<Message> toolCallMessageConversation = this.handleToolCallRequests(prompt.getInstructions(), response);
			return this.call(new Prompt(toolCallMessageConversation, prompt.getOptions()));

		}

		List<Generation> generations = response.getCandidatesList()
			.stream()
			.map(candidate -> candidate.getContent().getPartsList())
			.flatMap(List::stream)
			.map(Part::getText)
			.map(t -> new Generation(t))
			.toList();

		return new ChatResponse(generations, toChatResponseMetadata(response));
	}

	public List<Message> handleToolCallRequests(List<Message> previousMessages, GenerateContentResponse response) {

		Content assistantContent = response.getCandidatesList().get(0).getContent();

		List<AssistantMessage.ToolCall> assistantToolCalls = assistantContent.getPartsList()
			.stream()
			.filter(part -> part.hasFunctionCall())
			.map(part -> {
				FunctionCall functionCall = part.getFunctionCall();
				var functionName = functionCall.getName();
				String functionArguments = structToJson(functionCall.getArgs());
				return new AssistantMessage.ToolCall("", "function", functionName, functionArguments);
			})
			.toList();

		AssistantMessage assistantMessage = new AssistantMessage("", Map.of(), assistantToolCalls);

		ToolResponseMessage toolResponseMessage = this.executeFuncitons(assistantMessage);

		// History
		List<Message> toolCallMessageConversation = new ArrayList<>(previousMessages);
		toolCallMessageConversation.add(assistantMessage);
		toolCallMessageConversation.add(toolResponseMessage);
		return toolCallMessageConversation;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		try {

			var request = createGeminiRequest(prompt);

			ResponseStream<GenerateContentResponse> responseStream = request.model
				.generateContentStream(request.contents);

			return Flux.fromStream(responseStream.stream()).switchMap(response -> {
				if (this.isToolFunctionCall(response)) {
					List<Message> toolCallMessageConversation = this.handleToolCallRequests(prompt.getInstructions(),
							response);
					// Recursively call the stream method with the tool call message
					// conversation that contains the call responses.
					return this.stream(new Prompt(toolCallMessageConversation, prompt.getOptions()));
				}

				return Mono.just(response).map(response2 -> {
					List<Generation> generations = response.getCandidatesList()
						.stream()
						.map(candidate -> candidate.getContent().getPartsList())
						.flatMap(List::stream)
						.map(Part::getText)
						.map(t -> new Generation(t))
						.toList();

					return new ChatResponse(generations, toChatResponseMetadata(response));

				});
			});
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to generate content", e);
		}
	}

	private VertexAiChatResponseMetadata toChatResponseMetadata(GenerateContentResponse response) {
		return new VertexAiChatResponseMetadata(new VertexAiUsage(response.getUsageMetadata()));
	}

	@JsonInclude(Include.NON_NULL)
	public record GeminiRequest(List<Content> contents, GenerativeModel model) {
	}

	private GeminiRequest createGeminiRequest(Prompt prompt) {

		Set<String> functionsForThisRequest = new HashSet<>();

		GenerationConfig generationConfig = this.generationConfig;

		var generativeModelBuilder = new GenerativeModel.Builder().setModelName(this.defaultOptions.getModel())
			.setVertexAi(this.vertexAI);

		VertexAiGeminiChatOptions updatedRuntimeOptions = null;

		if (prompt.getOptions() != null) {
			updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
					VertexAiGeminiChatOptions.class);

			functionsForThisRequest
				.addAll(handleFunctionCallbackConfigurations(updatedRuntimeOptions, IS_RUNTIME_CALL));
		}

		if (this.defaultOptions != null) {

			functionsForThisRequest.addAll(handleFunctionCallbackConfigurations(this.defaultOptions, !IS_RUNTIME_CALL));

			if (updatedRuntimeOptions == null) {
				updatedRuntimeOptions = VertexAiGeminiChatOptions.builder().build();
			}

			updatedRuntimeOptions = ModelOptionsUtils.merge(updatedRuntimeOptions, this.defaultOptions,
					VertexAiGeminiChatOptions.class);

		}

		if (updatedRuntimeOptions != null) {

			if (StringUtils.hasText(updatedRuntimeOptions.getModel())
					&& !updatedRuntimeOptions.getModel().equals(this.defaultOptions.getModel())) {
				// Override model name
				generativeModelBuilder.setModelName(updatedRuntimeOptions.getModel());
			}

			generationConfig = toGenerationConfig(updatedRuntimeOptions);
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {
			List<Tool> tools = this.getFunctionTools(functionsForThisRequest);
			generativeModelBuilder.setTools(tools);
		}

		generativeModelBuilder.setGenerationConfig(generationConfig);

		GenerativeModel generativeModel = generativeModelBuilder.build();

		String systemContext = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.SYSTEM)
			.map(m -> m.getContent())
			.collect(Collectors.joining(System.lineSeparator()));

		if (StringUtils.hasText(systemContext)) {
			generativeModel.withSystemInstruction(ContentMaker.fromString(systemContext));
		}

		return new GeminiRequest(toGeminiContent(prompt), generativeModel);
	}

	private GenerationConfig toGenerationConfig(VertexAiGeminiChatOptions options) {

		GenerationConfig.Builder generationConfigBuilder = GenerationConfig.newBuilder();

		if (options.getTemperature() != null) {
			generationConfigBuilder.setTemperature(options.getTemperature());
		}
		if (options.getMaxOutputTokens() != null) {
			generationConfigBuilder.setMaxOutputTokens(options.getMaxOutputTokens());
		}
		if (options.getTopK() != null) {
			generationConfigBuilder.setTopK(options.getTopK());
		}
		if (options.getTopP() != null) {
			generationConfigBuilder.setTopP(options.getTopP());
		}
		if (options.getCandidateCount() != null) {
			generationConfigBuilder.setCandidateCount(options.getCandidateCount());
		}
		if (options.getStopSequences() != null) {
			generationConfigBuilder.addAllStopSequences(options.getStopSequences());
		}

		return generationConfigBuilder.build();
	}

	private List<Content> toGeminiContent(Prompt prompt) {

		List<Content> contents = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT
					|| m.getMessageType() == MessageType.TOOL)
			.map(message -> Content.newBuilder()
				.setRole(toGeminiMessageType(message.getMessageType()).getValue())
				.addAllParts(messageToGeminiParts(message))
				.build())
			.toList();

		return contents;
	}

	private static GeminiMessageType toGeminiMessageType(@NonNull MessageType type) {

		Assert.notNull(type, "Message type must not be null");

		switch (type) {
			case USER:
			case TOOL:
				return GeminiMessageType.USER;
			case ASSISTANT:
				return GeminiMessageType.MODEL;
			default:
				throw new IllegalArgumentException("Unsupported message type: " + type);
		}
	}

	static List<Part> messageToGeminiParts(Message message) {

		if (message instanceof UserMessage userMessage) {

			String messageTextContent = (userMessage.getContent() == null) ? "null" : userMessage.getContent();
			Part textPart = Part.newBuilder().setText(messageTextContent).build();

			List<Part> parts = new ArrayList<>(List.of(textPart));

			List<Part> mediaParts = userMessage.getMedia()
				.stream()
				.map(mediaData -> PartMaker.fromMimeTypeAndData(mediaData.getMimeType().toString(),
						mediaData.getData()))
				.toList();

			if (!CollectionUtils.isEmpty(mediaParts)) {
				parts.addAll(mediaParts);
			}

			return parts;
		}
		else if (message instanceof AssistantMessage assistantMessage) {
			List<Part> parts = new ArrayList<>();
			if (StringUtils.hasText(assistantMessage.getContent())) {
				List.of(Part.newBuilder().setText(assistantMessage.getContent()).build());
			}
			if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
				parts.addAll(assistantMessage.getToolCalls()
					.stream()
					.map(toolCall -> Part.newBuilder()
						.setFunctionCall(FunctionCall.newBuilder()
							.setName(toolCall.name())
							.setArgs(jsonToStruct(toolCall.arguments()))
							.build())
						.build())
					.toList());
			}
			return parts;
		}
		else if (message instanceof ToolResponseMessage toolResponseMessage) {

			return toolResponseMessage.getResponses()
				.stream()
				.map(response -> Part.newBuilder()
					.setFunctionResponse(FunctionResponse.newBuilder()
						.setName(response.name())
						.setResponse(jsonToStruct(response.responseData()))
						.build())
					.build())
				.toList();
		}
		else {
			throw new IllegalArgumentException("Gemini doesn't support message type: " + message.getClass());
		}
	}

	private List<Tool> getFunctionTools(Set<String> functionNames) {

		final var tool = Tool.newBuilder();

		final List<FunctionDeclaration> functionDeclarations = this.resolveFunctionCallbacks(functionNames)
			.stream()
			.map(functionCallback -> FunctionDeclaration.newBuilder()
				.setName(functionCallback.getName())
				.setDescription(functionCallback.getDescription())
				.setParameters(jsonToSchema(functionCallback.getInputTypeSchema()))
				.build())
			.toList();
		tool.addAllFunctionDeclarations(functionDeclarations);
		return List.of(tool.build());
	}

	private static String structToJson(Struct struct) {
		try {
			return JsonFormat.printer().print(struct);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static Struct jsonToStruct(String json) {
		try {
			var structBuilder = Struct.newBuilder();
			JsonFormat.parser().ignoringUnknownFields().merge(json, structBuilder);
			return structBuilder.build();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static Schema jsonToSchema(String json) {
		try {
			var schemaBuilder = Schema.newBuilder();
			JsonFormat.parser().ignoringUnknownFields().merge(json, schemaBuilder);
			return schemaBuilder.build();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private GenerateContentResponse getContentResponse(GeminiRequest request) {
		try {
			return request.model.generateContent(request.contents);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to generate content", e);
		}
	}

	@Override
	protected boolean isToolFunctionCall(GenerateContentResponse response) {
		if (response == null || CollectionUtils.isEmpty(response.getCandidatesList())
				|| response.getCandidatesList().get(0).getContent() == null
				|| CollectionUtils.isEmpty(response.getCandidatesList().get(0).getContent().getPartsList())) {
			return false;
		}
		return response.getCandidatesList().get(0).getContent().getPartsList().get(0).hasFunctionCall();
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return VertexAiGeminiChatOptions.fromOptions(this.defaultOptions);
	}

	@Override
	public void destroy() throws Exception {
		if (this.vertexAI != null) {
			this.vertexAI.close();
		}
	}

}
