const https = require('https');

const apiKey = 'sk-REDACTED';
const url = 'newapi.makelove.cloud';
const path = '/v1/chat/completions';

function post(body) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify(body);
    const options = {
      hostname: url,
      port: 443,
      path: path,
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
      }
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => {
        data += chunk;
      });
      res.on('end', () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
    });

    req.on('error', (e) => {
      reject(e);
    });

    req.write(postData);
    req.end();
  });
}

const promptTemplate = (query) => `
Analyze the user's message and determine their intent:
- If they are asking for recommendations, list of titles, suggestions of what to read, or similar series, return "RECOMMENDATION".
- If they are asking general questions about characters, plots, authors, lore, or having a general conversation (even if they mention a comic title), return "CONVERSATION".
Respond with exactly one word: "RECOMMENDATION" or "CONVERSATION". Do not include any punctuation or extra text.

User message: "${query}"
`;

async function testIntent(query) {
  const models = ['gemini-3.1-flash-lite', 'gpt-5.4-mini', 'grok-3-mini-fast'];
  for (const model of models) {
    console.log(`Testing model: "${model}" with query: "${query}"`);
    try {
      const response = await post({
        model: model,
        messages: [
          {
            role: 'system',
            content: 'You are a helpful intent classification assistant.'
          },
          {
            role: 'user',
            content: promptTemplate(query)
          }
        ],
        temperature: 0.0,
        max_tokens: 10
      });
      console.log(`Model "${model}" status:`, response.statusCode);
      if (response.statusCode === 200) {
        const parsed = JSON.parse(response.body);
        console.log(`Model "${model}" response:`, parsed.choices[0].message.content.trim());
      } else {
        console.log(`Model "${model}" error:`, response.body);
      }
    } catch (error) {
      console.error(`Model "${model}" exception:`, error.message);
    }
  }
}

async function run() {
  await testIntent("recommend me ntr + incest");
  console.log('-----------------------------');
  await testIntent("there's no ntr in it");
  console.log('-----------------------------');
  await testIntent("ntr");
  console.log('-----------------------------');
  await testIntent("incest");
}

run();
