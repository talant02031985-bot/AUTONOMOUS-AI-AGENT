export default {
  async fetch(request, env, ctx) {
    return Response.json({
      ok: true,
      service: "AYANA AI"
    });
  }
};
