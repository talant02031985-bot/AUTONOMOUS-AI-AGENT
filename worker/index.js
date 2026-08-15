export default {
  async fetch(request, env) {

    const url = new URL(request.url);

    // =========================================
    // ПРОВЕРКА СЕРВЕРА
    // =========================================

    if (request.method === "GET") {
      return Response.json({
        ok: true,
        service: "AYANA AI",
        ai: "ready",
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

    // =========================================
    // НЕЙРОННЫЙ ГОЛОС AYANA
    // POST /tts
    // body: { "text": "..." }
    // =========================================

    if (url.pathname === "/tts") {

      try {
        const body = await request.json();
        const text = body.text?.trim();

        if (!text) {
          return Response.json(
            { error: "Text is required" },
            { status: 400 }
          );
        }

        // Защита от слишком длинной озвучки
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
Говори по-русски как молодая девушка
в обычном дружеском разговоре.

Голос мягкий, светлый, женственный и живой.

Манера лёгкая и непринуждённая.
Не говори как диктор, оператор,
ведущая или сотрудник поддержки.

Не используй официальную манеру.

Тембр мягкий и приятный.
Избегай грубого, тяжёлого
и слишком низкого звучания.

Добавляй лёгкую улыбку
и небольшую живую эмоциональность.

Можно естественно менять интонацию
внутри фразы.

Не проговаривай каждое слово
слишком тщательно.

Не растягивай окончания.

Паузы короткие и естественные.

Звучание взрослое, но молодое.
Не детское и не писклявое.

Главное — ощущение живой,
приятной собеседницы.

Это голос AYANA.
              `.trim()
            })
          }
        );

        if (!ttsResponse.ok) {

          const errorText =
            await ttsResponse.text();

          return Response.json(
            {
              error: "OpenAI TTS error",
              details: errorText
            },
            { status: ttsResponse.status }
          );
        }

        const audio =
          await ttsResponse.arrayBuffer();

        return new Response(audio, {
          status: 200,

          headers: {
            "Content-Type": "audio/mpeg",
            "Cache-Control": "no-store",
            "X-Ayana-Voice": "marin"
          }
        });

      } catch (error) {

        return Response.json(
          {
            error: "AYANA TTS server error",
            details: String(error)
          },
          { status: 500 }
        );
      }
    }

    // =========================================
    // ТЕКСТОВЫЙ ИИ AYANA
    // POST /
    // body: { "message": "..." }
    // =========================================

    try {

      const body = await request.json();
      const message = body.message?.trim();

      if (!message) {

        return Response.json(
          { error: "Message is required" },
          { status: 400 }
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

            reasoning: {
              effort: "low"
            },

            instructions: `
Ты AYANA AI — персональный голосовой ИИ-помощник.

Основной язык — русский.

Если пользователь говорит по-кыргызски,
отвечай по-кыргызски.

Отвечай естественно,
дружелюбно и уверенно.

Твои ответы будут произноситься голосом,
поэтому формулируй их так,
как говорит живой человек.

Не используй Markdown,
если он не нужен.

Не перечисляй длинные списки,
если можно объяснить естественной речью.

Обычно отвечай кратко,
но если вопрос требует объяснения,
можешь ответить подробнее.

Не начинай каждый ответ
со слов "Я Аяна".

Не говори,
что ты языковая модель.
            `.trim(),

            input: message,

            max_output_tokens: 700
          })
        }
      );

      const data =
        await response.json();

      if (!response.ok) {

        return Response.json(
          {
            error: "OpenAI API error",
            details: data
          },
          { status: response.status }
        );
      }

      const reply =
        (data.output || [])
          .flatMap(item => item.content || [])
          .filter(
            item => item.type === "output_text"
          )
          .map(item => item.text)
          .join("\n")
          .trim();

      return Response.json({
        ok: true,

        reply:
          reply ||
          "Я пока не смогла сформировать ответ."
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
