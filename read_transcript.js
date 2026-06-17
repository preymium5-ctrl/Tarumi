const fs = require('fs');

const logPath = 'C:/Users/Rockz/.gemini/antigravity-ide/brain/319026f8-0ea1-4778-91a8-40d36f595b07/.system_generated/logs/transcript.jsonl';
const lines = fs.readFileSync(logPath, 'utf8').split('\n');

const obj = JSON.parse(lines[16695]);
console.log("FULL REPORT:");
console.log(obj.content);
