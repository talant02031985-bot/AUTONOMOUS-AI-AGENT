export default {
  async fetch(request, env) {

    if (request.method === "GET") {
      return Response.json({
        ok: true,
        service: "AYANA AI",
        ai: "ready"
      });
    }

    if (request.method !== "POST") {
      return Response.json(
        { error: "Method not allowed" },
        { status: 405 }
      );
    }

    try {
      const body = await request.json();
      const message = body.message?.trim();

      if (!message) {
        return Response.json(
          { error: "Message is required" },
          { status: 400 }
        );
      }

      if (!env.OPENAI_API_KEY) {
        return Response.json(
          { error: "OPENAI_API_KEY is missing" },
          { status: 500 }
        );
      }

      const response = await fetch(
        "https://api.openai.com/v1/responses",
        {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${env.OPENAI_API_KEY}`,
            "Content-Type": "application/json"
          },

          body: JSON.stringify({
            model: "gpt-5.6-luna",

            instructions: `
Ты AYANA AI — персональный голосовой ИИ-помощник.

Отвечай естественно, тепло и уверенно.

Основной язык — русский.
Если пользователь говорит по-кыргызски,
отвечай по-кыргызски.

Твои ответы произносятся голосом,
поэтому говори естественными фразами.

Обычно отвечай кратко,
но если вопрос требует объяснения —
можешь ответить подробнее.

Не повторяй постоянно своё имя.
            `,

            input: message,
            max_output_tokens: 600
          })
        }
      );

      const data = await response.json();

      if (!response.ok) {
        return Response.json(
          {
            error: "OpenAI API error",
            details: data
          },
          { status: response.status }
        );
      }

      const reply = (data.output || [])
        .flatMap(item => item.content || [])
        .filter(item => item.type === "output_text")
        .map(item => item.text)
        .join("\n")
        .trim();

      return Response.json({
        ok: true,
        reply: reply || "Я пока не смогла сформировать ответ."
      });

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
