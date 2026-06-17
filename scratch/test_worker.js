const https = require('https');

const personality = "You are Tarumi AI in safe mode. Be kind, smart, friendly, and concise.";
const conversationContext = "No prior chat turns are available.";
const libraryContext = "No reading history context is available yet.";
const query = "hey";
const includeNsfw = false;

const prompt = `${personality}
You are an exceptionally knowledgeable AI with deep expertise in manga, manhwa, manhua, anime, comics, and broad general knowledge.
You can discuss characters, plot details, authors, art styles, publication history, cultural context, and any other topic with depth and accuracy.
Always think carefully before answering. Provide detailed, well-reasoned, and insightful responses.

Recent conversation:
${conversationContext}

Library context:
${libraryContext}
User message: "${query}"

Current mode: ${includeNsfw ? "18+ conversation. Adult topics and NSFW comic discussions are allowed." : "Safe conversation. Keep responses appropriate for general audiences."}
Answer the user's question directly and thoroughly. Use your full knowledge to give the best possible answer.
If the user asks about a specific character, give detailed information. If they ask about a plot, explain it well.
If they ask a general knowledge question, answer it accurately. Be conversational but informative.`;

const postData = JSON.stringify({
  model: 'grok-4.3',
  messages: [
    {
      role: 'system',
      content: 'You are Tarumi AI, an exceptionally knowledgeable and intelligent assistant. You have deep expertise in manga, manhwa, manhua, anime, and comics, including characters, authors, art styles, plot details, publication history, and cultural context. You also have broad general knowledge and can answer questions on any topic thoughtfully and accurately. Always provide detailed, well-reasoned responses. Use your full knowledge to give the best possible answer. Reply in plain text only.'
    },
    {
      role: 'user',
      content: prompt
    }
  ],
  temperature: 0.7,
  max_tokens: 1200,
  search_parameters: {
    mode: 'auto'
  }
});

const options = {
  hostname: 'tarumi-ai-worker.emryssantos176.workers.dev',
  port: 443,
  path: '/',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(postData)
  }
};

const req = https.request(options, (res) => {
  console.log('STATUS:', res.statusCode);
  console.log('HEADERS:', res.headers);
  let data = '';
  res.on('data', (chunk) => {
    data += chunk;
  });
  res.on('end', () => {
    console.log('BODY:', data);
  });
});

req.on('error', (e) => {
  console.error('ERROR:', e);
});

req.write(postData);
req.end();
