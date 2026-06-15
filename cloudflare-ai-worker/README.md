# Tarumi AI Worker

Cloudflare Worker proxy for Tarumi Ask AI. The Android app sends OpenAI-compatible chat completion payloads here. The Worker injects the real provider API key server-side and forwards the request upstream.

## Setup

Install dependencies:

```powershell
npm install
```

Set the provider API key as a Cloudflare secret:

```powershell
npx wrangler secret put AI_API_KEY
```

Optional: require a lightweight app header token:

```powershell
npx wrangler secret put CLIENT_AUTH_TOKEN
```

Deploy:

```powershell
npx wrangler deploy
```

After deploy, add these to the Android `local.properties` file:

```properties
ai_cloud_proxy_url=https://tarumi-ai-worker.<your-account>.workers.dev
ai_cloud_proxy_model=gpt-4.1-mini
ai_cloud_proxy_client_token=<same value as CLIENT_AUTH_TOKEN, if configured>
```

Do not put `AI_API_KEY` in Android source, `local.properties`, resources, or BuildConfig.
