interface Env {
  AI_API_KEY: string;
  AI_API_BASE_URL?: string;
  AI_MODEL?: string;
  CLIENT_AUTH_TOKEN?: string;
}

const JSON_HEADERS = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-Tarumi-Client-Token",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    if (request.method === "GET") {
      return Response.json({ ok: true, service: "tarumi-ai-worker" }, { headers: JSON_HEADERS });
    }

    if (request.method !== "POST") {
      return Response.json({ error: "Method not allowed" }, { status: 405, headers: JSON_HEADERS });
    }

    if (!env.AI_API_KEY) {
      return Response.json({ error: "AI_API_KEY is not configured" }, { status: 500, headers: JSON_HEADERS });
    }

    if (env.CLIENT_AUTH_TOKEN) {
      const token = request.headers.get("X-Tarumi-Client-Token");
      if (token !== env.CLIENT_AUTH_TOKEN) {
        return Response.json({ error: "Unauthorized" }, { status: 401, headers: JSON_HEADERS });
      }
    }

    const payload = await readJsonObject(request);
    if (!payload) {
      return Response.json({ error: "Invalid JSON body" }, { status: 400, headers: JSON_HEADERS });
    }

    delete payload.search_parameters;
    if (env.AI_MODEL) {
      payload.model = env.AI_MODEL;
    }

    const upstreamResponse = await fetch(env.AI_API_BASE_URL || "https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${env.AI_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    return new Response(await upstreamResponse.text(), {
      status: upstreamResponse.status,
      headers: JSON_HEADERS,
    });
  },
};

async function readJsonObject(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) {
      return null;
    }
    return body as Record<string, unknown>;
  } catch {
    return null;
  }
}
