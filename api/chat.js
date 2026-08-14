export default async function handler(request) {

  if (request.method !== "POST") {
    return new Response(
      JSON.stringify({
        error: "Method not allowed"
      }),
      {
        status: 405,
        headers: {
          "Content-Type": "application/json"
        }
      }
    );
  }

  try {

    const body = await request.json();
    const message = body.message?.trim();

    if (!message) {
      return new Response(
        JSON.stringify({
          error: "Message is required"
        }),
        {
          status: 400,
          headers: {
            "Content-Type": "application/json"
          }
        }
      );
    }

    const apiKey = process.env.OPENAI_API_KEY;

    if (!apiKey) {
      return new Response(
        JSON.stringify({
          error: "OPENAI_API_KEY is not configured"
        }),
        {
          status: 500,
          headers: {
            "Content-Type": "application/json"
          }
        }
      );
    }

    const openaiResponse = await fetch(
      "https://api.openai.com/v1/responses",
      {
        method: "POST",

        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },

        body: JSON.stringify({

          model: "gpt-5.6-luna",

          instructions: `
Ты AYANA AI — персональный голосовой ИИ-помощник.

Отвечай естественно, дружелюбно и уверенно.

Пользователь разговаривает с тобой голосом,
поэтому ответы должны хорошо звучать вслух.

По умолчанию отвечай по-русски.
Если пользователь говорит по-кыргызски —
отвечай по-кыргызски.

Не начинай каждый ответ со своего имени.
Не говори, что ты языковая модель.

Для голосового диалога предпочитай
короткие и содержательные ответы.
          `,

          input: message,

          max_output_tokens: 500
        })
      }
    );

    const data = await openaiResponse.json();

    if (!openaiResponse.ok) {
      return new Response(
        JSON.stringify({
          error: "OpenAI request failed",
          details: data
        }),
        {
          status: openaiResponse.status,
          headers: {
            "Content-Type": "application/json"
          }
        }
      );
    }

    const reply = (data.output || [])
      .flatMap(item => item.content || [])
      .filter(part => part.type === "output_text")
      .map(part => part.text)
      .join("\n")
      .trim();

    return new Response(
      JSON.stringify({
        reply: reply || "Я не смогла сформировать ответ.",
        responseId: data.id
      }),
      {
        status: 200,
        headers: {
          "Content-Type": "application/json"
        }
      }
    );

  } catch (error) {

    return new Response(
      JSON.stringify({
        error: "Server error"
      }),
      {
        status: 500,
        headers: {
          "Content-Type": "application/json"
        }
      }
    );
  }
}
