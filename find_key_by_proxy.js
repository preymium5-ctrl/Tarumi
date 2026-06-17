const fs = require('fs');

process.on('uncaughtException', (err) => {
    console.log("[Uncaught Exception Logged]:", err.message);
});
process.on('unhandledRejection', (reason) => {
    console.log("[Unhandled Rejection Logged]:", reason.message || reason);
});

// We will use a Proxy to trace all accesses to global/window properties.
const accessedProps = new Set();
const loggedAccesses = [];

const globalProxy = new Proxy(global, {
    get(target, prop, receiver) {
        const propStr = String(prop);
        if (!accessedProps.has(propStr)) {
            accessedProps.add(propStr);
            loggedAccesses.push({ type: 'get', prop: propStr });
            console.log(`[Proxy GET] window.${propStr}`);
        }
        
        // Mock specific objects if accessed
        if (prop === 'document') {
            return documentProxy;
        }
        if (prop === 'navigator') {
            return { userAgent: 'Mozilla/5.0', appCodeName: 'Mozilla' };
        }
        if (prop === 'location') {
            return { href: 'https://comix.to/', protocol: 'https:', hostname: 'comix.to', pathname: '/' };
        }
        
        return target[prop];
    },
    set(target, prop, value, receiver) {
        console.log(`[Proxy SET] window.${String(prop)} = ${typeof value === 'function' ? 'function' : value}`);
        target[prop] = value;
        return true;
    }
});

const documentAccessed = new Set();
const documentProxy = new Proxy({
    documentElement: {},
    body: {},
    addEventListener: () => {},
    removeEventListener: () => {},
}, {
    get(target, prop) {
        const propStr = String(prop);
        if (!documentAccessed.has(propStr)) {
            documentAccessed.add(propStr);
            console.log(`[Proxy GET] document.${propStr}`);
        }
        
        // If it looks for elements, let's log the query
        if (prop === 'getElementById') {
            return (id) => {
                console.log(`[Proxy CALL] document.getElementById("${id}")`);
                return null;
            };
        }
        if (prop === 'querySelector') {
            return (selector) => {
                console.log(`[Proxy CALL] document.querySelector("${selector}")`);
                return null;
            };
        }
        if (prop === 'querySelectorAll') {
            return (selector) => {
                console.log(`[Proxy CALL] document.querySelectorAll("${selector}")`);
                return [];
            };
        }
        if (prop === 'getElementsByTagName') {
            return (tag) => {
                console.log(`[Proxy CALL] document.getElementsByTagName("${tag}")`);
                if (tag === 'meta') {
                    // Let's return some mock meta tags
                    return [
                        {
                            name: 'cfg',
                            getAttribute: (name) => {
                                console.log(`[Proxy CALL] meta.getAttribute("${name}")`);
                                if (name === 'content') {
                                    return 'ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
                                }
                                return null;
                            }
                        }
                    ];
                }
                return [];
            };
        }
        
        return target[prop];
    }
});

Object.defineProperty(global, 'navigator', {
    value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        appCodeName: 'Mozilla'
    },
    configurable: true,
    writable: true
});
Object.defineProperty(global, 'location', {
    value: {
        href: 'https://comix.to/',
        protocol: 'https:',
        hostname: 'comix.to',
        pathname: '/'
    },
    configurable: true,
    writable: true
});
global.window = globalProxy;

// Override global.fetch to handle relative URLs
const originalFetch = global.fetch;
global.fetch = (url, options) => {
    let targetUrl = url;
    if (typeof url === 'string' && url.startsWith('/')) {
        targetUrl = 'https://comix.to' + url;
    }
    console.log(`[Fetch intercepted] ${url} -> ${targetUrl}`);
    return originalFetch(targetUrl, options).catch(err => {
        console.log(`[Fetch Failed] ${url}:`, err.message);
        throw err;
    });
};

try {
    const code = fs.readFileSync('secure.js', 'utf8');
    const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
    
    // We run the code using the Function constructor or eval, mapping window/global/globalThis to our proxy.
    // To do this, we can wrap cleanCode in a function that receives 'window' and 'globalThis' as arguments.
    const runFn = new Function('window', 'globalThis', 'document', cleanCode);
    runFn(globalProxy, globalProxy, documentProxy);
    
    // Let's see what keys are defined
    const vmKeys = Object.keys(global).filter(k => k.startsWith('vm'));
    console.log("Successfully ran! vmKeys:", vmKeys);
    if (vmKeys.length > 0) {
        (async () => {
            const vmObj = global[vmKeys[0]];
            console.log("Analyzing object keys of", vmKeys[0]);
            const probePath = "/manga/g2rk/chapters";
            console.log(`\nTesting functions on ${vmKeys[0]} with path "${probePath}":`);
            for (const key of Object.keys(vmObj)) {
                const value = vmObj[key];
                if (typeof value === 'function') {
                    try {
                        accessedProps.clear();
                        documentAccessed.clear();
                        const result = value(probePath);
                        if (result instanceof Promise) {
                            console.log(`  vmObj.${key}("${probePath}") -> returned Promise`);
                            const resolved = await result;
                            console.log(`    vmObj.${key} resolved -> type: ${typeof resolved}, value: ${resolved}`);
                        } else if (result) {
                            console.log(`  vmObj.${key}("${probePath}") -> type: ${typeof result}, length: ${result.length}, value: ${result}`);
                        }
                    } catch (e) {
                        console.log(`  vmObj.${key} failed:`, e.message);
                    }
                }
            }
        })();
    }
    
} catch (err) {
    console.error("Execution failed:", err.message);
    console.error(err.stack);
}
