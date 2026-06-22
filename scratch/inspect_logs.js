const fs = require('fs');

const logPath = 'C:/Users/Rockz/.gemini/antigravity-ide/brain/bc79b9e0-c060-4689-b2e0-d71fc6b6497e/.system_generated/logs/transcript.jsonl';

if (!fs.existsSync(logPath)) {
    console.error("Log file does not exist:", logPath);
    process.exit(1);
}

const lines = fs.readFileSync(logPath, 'utf8').split('\n');

for (let idx = 0; idx < lines.length; idx++) {
    const line = lines[idx];
    if (!line.trim()) continue;
    try {
        const obj = JSON.parse(line);
        const str = JSON.stringify(obj);
        if (str.includes("ComixParser.kt") && str.includes("Showing lines")) {
            console.log(`Line ${idx}: type=${obj.type}, status=${obj.status}, keys=${Object.keys(obj).join(',')}`);
            if (obj.tool_calls) {
                console.log("  has tool_calls");
            }
            // Print snippet of content/output
            const text = obj.content || obj.output || "";
            console.log("  text length:", text.length);
            console.log("  snippet:", text.substring(0, 100));
        }
    } catch (e) {}
}
