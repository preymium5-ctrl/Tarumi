const https = require('https');

const apiKey = 'sk-REDACTED';
const url = 'newapi.makelove.cloud';
const path = '/v1/chat/completions';

const base64Image = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='; // 1x1 red pixel

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

async function runTestCustom() {
  console.log('Testing custom format...');
  try {
    const response = await post({
      model: 'grok-4.3',
      messages: [
        {
          role: 'user',
          content: [
            {
              type: 'input_image',
              image_url: `data:image/png;base64,${base64Image}`
            },
            {
              type: 'input_text',
              text: 'Identify the color of this 1x1 image.'
            }
          ]
        }
      ],
      max_tokens: 50
    });
    console.log('Custom Response Status:', response.statusCode);
    console.log('Custom Response Body:', response.body);
  } catch (error) {
    console.error('Custom Format Error:', error.message);
  }
}

async function runTestStandard() {
  console.log('Testing standard OpenAI format...');
  try {
    const response = await post({
      model: 'grok-4.3',
      messages: [
        {
          role: 'user',
          content: [
            {
              type: 'image_url',
              image_url: {
                url: `data:image/png;base64,${base64Image}`
              }
            },
            {
              type: 'text',
              text: 'Identify the color of this 1x1 image.'
            }
          ]
        }
      ],
      max_tokens: 50
    });
    console.log('Standard Response Status:', response.statusCode);
    console.log('Standard Response Body:', response.body);
  } catch (error) {
    console.error('Standard Format Error:', error.message);
  }
}

async function run() {
  await runTestCustom();
  console.log('-----------------------------');
  await runTestStandard();
}

run();
