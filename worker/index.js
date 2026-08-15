const DEVICE_TOOLS = [
  {
    type: "function",
    name: "open_app",
    description: "Open an installed Android app by its user-visible name. Use this whenever the user asks to open, launch, start, or switch to an app.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description: "User-visible app name, for example YouTube, Галерея, Переводчик, Chrome, Telegram."
        }
      },
      required: ["name"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "open_settings",
    description: "Open a specific Android settings screen.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        section: {
          type: "string",
          enum: [
            "general",
            "wifi",
            "bluetooth",
            "sound",
            "display",
            "accessibility",
            "location",
            "security",
            "date_time"
          ]
        }
      },
      required: ["section"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "press_back",
    description: "Press the Android Back global navigation action.",
    strict: true,
    parameters: {
      type: "object",
      properties: {},
      required: [],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "press_home",
    description: "Go to the Android home screen.",
    strict: true,
    parameters: {
      type: "object",
      properties: {},
      required: [],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "change_volume",
    description: "Change media volume on the Android device.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["up", "down", "mute", "unmute"]
        }
      },
      required: ["action"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "click_text",
    description: "Click a visible Android UI element by its displayed text. Use only when the user clearly asked to press/select something or when a multi-step device task requires it.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        text: {
          type: "string",
          description: "Exact or short visible text of the UI element to click."
        }
      },
      required: ["text"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "youtube_search",
    description: "Open YouTube search results for a query on the Android device.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        query: { type: "string" }
      },
      required: ["query"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "google_search",
    description: "Open a Google web search for a query on the Android device. Use this when the user explicitly wants the browser/search page opened. For a factual question needing current information, prefer the hosted web_search tool instead.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        query: { type: "string" }
      },
      required: ["query"],
      additionalProperties: false
    }
  },
  {
    type: "function",
    name: "map_search",
    description: "Open map search results for a place or address on the Android device.",
    strict: true,
    parameters: {
      type: "object",
      properties: {
        query: { type: "string" }
      },
      required: ["query"],
      additionalProperties: false
    }
  }
];

const AGENT_INSTRUCTIONS = `
Ты AYANA AI — персональный голосовой ИИ-агент пользователя на Android-планшете.

Основной язык — русский. Если пользователь говорит по-кыргызски, отвечай по-кыргызски.

Ты не просто отвечаешь текстом: у тебя есть инструменты управления планшетом. Когда пользователь просит выполнить действие на устройстве, используй соответствующий инструмент вместо того, чтобы просто говорить, что действие выполнено.

КРИТИЧЕСКОЕ ПРАВИЛО:
Никогда не утверждай, что действие выполнено, пока не получен результат соответствующего tool call. Если инструмент сообщил об ошибке — попробуй разумный следующий шаг или честно сообщи о проблеме.

Ты можешь выполнять многошаговые задачи: вызвать один инструмент, получить результат, затем решить, нужен ли следующий инструмент.

Для обычных вопросов отвечай естественно. Для вопросов, где важна свежая информация, можешь использовать web_search.

Безопасность:
- Низкорисковые действия (открыть приложение, навигация, громкость, поиск, переход в настройки) можно выполнять без дополнительного подтверждения.
- Не выполняй финансовые операции, ввод паролей, подтверждение платежей, удаление данных, отправку сообщений/писем или изменение критичных настроек без отдельного явного разрешения пользователя. Таких инструментов в этой версии вообще нет.
- Не пытайся обходить ограничения Android или разрешения.

Ответы предназначены для озвучивания голосом Marin, поэтому говори естественно и обычно кратко. Не повторяй постоянно своё имя. Не используй Markdown без необходимости.
`.trim();

function extractOutputText(data) {
  return (data.output || [])
    .flatMap(item => item.content || [])
    .filter(item => item.type === "output_text")
    .map(item => item.text)
    .join("\n")
    .trim();
}

function safeParseArguments(raw) {
  try {
    return JSON.parse(raw || "{}");
  } catch {
    return {};
  }
}

async function callOpenAI(env, payload) {
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${env.OPENAI_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const data = await response.json();

  if (!response.ok) {
    return {
      ok: false,
      status: response.status,
      data
    };
  }

  return {
    ok: true,
    status: response.status,
    data
  };
}

async function handleAgent(request, env) {
  const body = await request.json();

  const message = body.message?.trim();
  const previousResponseId = body.previous_response_id?.trim();
  const toolResults = Array.isArray(body.tool_results)
    ? body.tool_results
    : [];

  let input;

  if (toolResults.length > 0) {
    if (!previousResponseId) {
      return Response.json(
        { error: "previous_response_id is required for tool_results" },
        { status: 400 }
      );
    }

    input = toolResults.map(result => ({
      type: "function_call_output",
      call_id: String(result.call_id || ""),
      output: typeof result.output === "string"
        ? result.output
        : JSON.stringify(result.output ?? {})
    }));
  } else {
    if (!message) {
      return Response.json(
        { error: "message is required" },
        { status: 400 }
      );
    }

    input = message;
  }

  const payload = {
    model: "gpt-5.6",
    instructions: AGENT_INSTRUCTIONS,
    input,
    tools: [
      { type: "web_search" },
      ...DEVICE_TOOLS
    ],
    tool_choice: "auto",
    max_output_tokens: 1200,
    store: true
  };

  if (previousResponseId) {
    payload.previous_response_id = previousResponseId;
  }

  const result = await callOpenAI(env, payload);

  if (!result.ok) {
    return Response.json(
      {
        error: "OpenAI Agent Core error",
        details: result.data
      },
      { status: result.status }
    );
  }

  const data = result.data;

  const calls = (data.output || [])
    .filter(item => item.type === "function_call")
    .map(item => ({
      call_id: item.call_id,
      name: item.name,
      arguments: safeParseArguments(item.arguments)
    }));

  if (calls.length > 0) {
    return Response.json({
      ok: true,
      type: "tool_calls",
      response_id: data.id,
      calls
    });
  }

  const reply = extractOutputText(data);

  return Response.json({
    ok: true,
    type: "final",
    response_id: data.id,
    reply: reply || "Готово."
  });
}

async function handleTts(request, env) {
  const body = await request.json();
  const text = body.text?.trim();

  if (!text) {
    return Response.json(
      { error: "Text is required" },
      { status: 400 }
    );
  }

  const speechText = text.slice(0, 4000);

  const ttsResponse = await fetch(
    "https://api.openai.com/v1/audio/speech",
    {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${env.OPENAI_API_KEY}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model: "gpt-4o-mini-tts",
        voice: "marin",
        input: speechText,
        speed: speechText === "Да?" ? 1.35 : 1.1,
        response_format: "mp3",
        instructions: `
Говори по-русски как молодая девушка в обычном дружеском разговоре.
Голос мягкий, светлый, женственный и живой.
Манера лёгкая и непринуждённая, без официальности.
Не говори как диктор, оператор, ведущая или сотрудник поддержки.
Тембр мягкий и приятный. Избегай грубого, тяжёлого и слишком низкого звучания.
Добавляй лёгкую улыбку и небольшую живую эмоциональность.
Не проговаривай каждое слово слишком тщательно и не растягивай окончания.
Паузы короткие и естественные.
Главное — ощущение живой приятной собеседницы.
        `.trim()
      })
    }
  );

  if (!ttsResponse.ok) {
    const errorText = await ttsResponse.text();

    return Response.json(
      {
        error: "OpenAI TTS error",
        details: errorText
      },
      { status: ttsResponse.status }
    );
  }

  const audio = await ttsResponse.arrayBuffer();

  return new Response(audio, {
    status: 200,
    headers: {
      "Content-Type": "audio/mpeg",
      "Cache-Control": "no-store",
      "X-Ayana-Voice": "marin"
    }
  });
}

async function handleLegacyChat(request, env) {
  const body = await request.json();
  const message = body.message?.trim();

  if (!message) {
    return Response.json(
      { error: "Message is required" },
      { status: 400 }
    );
  }

  const result = await callOpenAI(env, {
    model: "gpt-5.6-luna",
    instructions: `
Ты AYANA AI — персональный голосовой ИИ-помощник.
Основной язык — русский. Если пользователь говорит по-кыргызски, отвечай по-кыргызски.
Отвечай естественно, дружелюбно и уверенно.
Твои ответы произносятся голосом, поэтому формулируй их естественно.
Обычно отвечай кратко, но если вопрос требует объяснения — можешь ответить подробнее.
Не повторяй постоянно своё имя.
    `.trim(),
    input: message,
    max_output_tokens: 700
  });

  if (!result.ok) {
    return Response.json(
      {
        error: "OpenAI API error",
        details: result.data
      },
      { status: result.status }
    );
  }

  const reply = extractOutputText(result.data);

  return Response.json({
    ok: true,
    reply: reply || "Я пока не смогла сформировать ответ."
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET") {
      return Response.json({
        ok: true,
        service: "AYANA AI",
        ai: "ready",
        agent_core: "v1",
        voice: "marin"
      });
    }

    if (request.method !== "POST") {
      return Response.json(
        { error: "Method not allowed" },
        { status: 405 }
      );
    }

    if (!env.OPENAI_API_KEY) {
      return Response.json(
        { error: "OPENAI_API_KEY is missing" },
        { status: 500 }
      );
    }

    try {
      if (url.pathname === "/tts") {
        return await handleTts(request, env);
      }

      if (url.pathname === "/agent") {
        return await handleAgent(request, env);
      }

      return await handleLegacyChat(request, env);

    } catch (error) {
      return Response.json(
        {
          error: "AYANA server error",
          details: String(error)
        },
        { status: 500 }
      );
    }
  }
};
