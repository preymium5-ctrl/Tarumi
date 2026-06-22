const fs = require('fs');

async function test() {
    try {
        console.log("Fetching live home page to find script URL...");
        const homeHtml = await fetch("https://comix.to/").then(r => r.text());
        const match = homeHtml.match(/<script type="module" src="([^"]+)"/);
        if (!match) {
            console.log("No module script found on home page. HTML length:", homeHtml.length);
            return;
        }
        const scriptUrl = new URL(match[1], "https://comix.to/").href;
        console.log("Found script URL:", scriptUrl);

        console.log("Fetching JS file...");
        const js = await fetch(scriptUrl).then(r => r.text());
        console.log("JS length:", js.length);

        const regex = /from\s*["']\.\/(env-[^"']+\.js)["']/;
        const regexMatch = js.match(regex);
        if (regexMatch) {
            console.log("Regex match success! Env module file:", regexMatch[1]);
        } else {
            console.log("Regex match FAILED!");
            // Let's print some sample imports to see what it looks like
            const importMatches = js.match(/from\s*["'][^"']+["']/g);
            console.log("All import statements found:", importMatches ? importMatches.slice(0, 10) : "none");
        }
    } catch (e) {
        console.error("Error:", e);
    }
}

test();
