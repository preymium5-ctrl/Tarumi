const fs = require('fs');

process.on('uncaughtException', (err) => {
    // Silently handle - the VM throws many non-critical errors
});
process.on('unhandledRejection', (reason) => {
    // Silently handle
});

// ---- Mock DOM Environment ----

// Canvas mock for fingerprinting
class MockCanvas {
    constructor(w, h) {
        this.width = w || 300;
        this.height = h || 150;
        this.style = {};
    }
    getContext(type) {
        if (type === '2d') {
            return {
                fillRect: () => {},
                clearRect: () => {},
                getImageData: (x, y, w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
                putImageData: () => {},
                createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
                setTransform: () => {},
                drawImage: () => {},
                save: () => {},
                fillText: () => {},
                restore: () => {},
                beginPath: () => {},
                moveTo: () => {},
                lineTo: () => {},
                closePath: () => {},
                stroke: () => {},
                translate: () => {},
                scale: () => {},
                rotate: () => {},
                arc: () => {},
                fill: () => {},
                measureText: (t) => ({ width: t.length * 7 }),
                transform: () => {},
                font: '',
                textBaseline: '',
                textAlign: '',
                fillStyle: '',
                strokeStyle: '',
                globalAlpha: 1,
                globalCompositeOperation: 'source-over',
                canvas: this,
            };
        }
        if (type === 'webgl' || type === 'webgl2') {
            return {
                getExtension: () => null,
                getParameter: (p) => p === 37446 ? 'Intel' : p === 37445 ? 'Google Inc.' : '',
                getSupportedExtensions: () => [],
            };
        }
        return null;
    }
    toDataURL() { return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=='; }
    toBlob(cb) { cb(new Blob()); }
    setAttribute() {}
    getAttribute() { return null; }
}

// ImageData mock
class MockImageData {
    constructor(w, h) {
        if (w instanceof Uint8ClampedArray) {
            this.data = w;
            this.width = h;
            this.height = arguments[2] || 1;
        } else {
            this.width = w || 1;
            this.height = h || 1;
            this.data = new Uint8ClampedArray(this.width * this.height * 4);
        }
    }
}

global.ImageData = MockImageData;

const cfgContent = 'ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';

const metaMock = {
    name: 'cfg',
    content: cfgContent,
    getAttribute(attr) {
        if (attr === 'name') return 'cfg';
        if (attr === 'content') return cfgContent;
        return null;
    }
};

const documentMock = {
    documentElement: { outerHTML: '<html></html>', innerText: '', style: {} },
    body: { innerText: '', appendChild: () => {}, removeChild: () => {}, style: {} },
    title: '',
    hidden: false,
    visibilityState: 'visible',
    addEventListener: () => {},
    removeEventListener: () => {},
    getElementById: (id) => null,
    querySelector: (sel) => {
        if (sel === '[disable-devtool-auto]') return null;
        if (sel === 'meta[name="cfg"]') return metaMock;
        return null;
    },
    querySelectorAll: (sel) => {
        if (sel === 'meta') return [metaMock];
        return [];
    },
    getElementsByTagName: (tag) => {
        if (tag === 'meta') return [metaMock];
        return [];
    },
    createElement: (tag) => {
        if (tag === 'canvas') return new MockCanvas();
        return {
            style: {},
            setAttribute: () => {},
            getAttribute: () => null,
            appendChild: () => {},
            removeChild: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            classList: { add: () => {}, remove: () => {}, contains: () => false },
            innerHTML: '',
            innerText: '',
            textContent: '',
            tagName: tag.toUpperCase(),
        };
    },
    createTextNode: (text) => ({ textContent: text }),
    cookie: '',
};

// Global mocks
Object.defineProperty(global, 'navigator', {
    value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        appCodeName: 'Mozilla',
        platform: 'Win32',
        language: 'en-US',
        languages: ['en-US', 'en'],
        cookieEnabled: true,
        hardwareConcurrency: 8,
        maxTouchPoints: 0,
        vendor: 'Google Inc.',
        plugins: { length: 0 },
        mimeTypes: { length: 0 },
    },
    configurable: true,
    writable: true
});

Object.defineProperty(global, 'location', {
    value: {
        href: 'https://comix.to/',
        protocol: 'https:',
        hostname: 'comix.to',
        host: 'comix.to',
        origin: 'https://comix.to',
        pathname: '/',
        search: '',
        hash: '',
        port: '',
    },
    configurable: true,
    writable: true
});

Object.defineProperty(global, 'document', {
    value: documentMock,
    configurable: true,
    writable: true
});

global.HTMLCanvasElement = MockCanvas;
global.CanvasRenderingContext2D = function() {};
global.OffscreenCanvasRenderingContext2D = function() {};
global.OffscreenCanvas = MockCanvas;
global.WebGLRenderingContext = function() {};
global.WebGL2RenderingContext = function() {};
global.createImageBitmap = async () => ({ width: 1, height: 1, close: () => {} });
global.Document = function() {};
global.open = () => null;
global.top = global;
global.parent = global;
global.innerWidth = 1920;
global.innerHeight = 1080;
global.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, pixelDepth: 24 };
global.devicePixelRatio = 1;
global.performance = { now: () => Date.now(), timing: { navigationStart: Date.now() } };
global.requestAnimationFrame = (cb) => setTimeout(cb, 16);
global.cancelAnimationFrame = (id) => clearTimeout(id);
global.MutationObserver = class { constructor() {} observe() {} disconnect() {} };
global.ResizeObserver = class { constructor() {} observe() {} disconnect() {} };
global.IntersectionObserver = class { constructor() {} observe() {} disconnect() {} };
global.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {}, clear: () => {} };
global.sessionStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {}, clear: () => {} };
global.Blob = class { constructor() { this.size = 0; this.type = ''; } };
global.URL = { createObjectURL: () => 'blob:null', revokeObjectURL: () => {} };

// Wrap window
global.window = new Proxy(global, {
    get(target, prop) {
        if (prop === 'document') return documentMock;
        return target[prop];
    },
    set(target, prop, value) {
        target[prop] = value;
        return true;
    }
});

// Mock fetch to intercept
const originalFetch = global.fetch;
global.fetch = async (url, options) => {
    let targetUrl = url;
    if (typeof url === 'string' && url.startsWith('/')) {
        targetUrl = 'https://comix.to' + url;
    }
    console.log(`[Fetch intercepted] ${url}`);
    if (originalFetch) {
        return originalFetch(targetUrl, options).catch(err => {
            console.log(`[Fetch Failed] ${err.message}`);
            throw err;
        });
    }
    throw new Error('No fetch available');
};

// ---- Run secure.js ----
try {
    const code = fs.readFileSync('secure.js', 'utf8');
    const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
    
    const runFn = new Function('window', 'globalThis', 'document', cleanCode);
    runFn(global.window, global.window, documentMock);
    
    // Find vmU_* objects
    const vmKeys = Object.keys(global).filter(k => /^vm[A-Za-z]_\w+$/.test(k));
    console.log("VM keys found:", vmKeys);
    
    if (vmKeys.length === 0) {
        console.log("No VM keys found!");
        process.exit(1);
    }
    
    const vmObj = global[vmKeys[0]];
    const allFnKeys = Object.keys(vmObj).filter(k => typeof vmObj[k] === 'function');
    console.log(`Found ${allFnKeys.length} functions in ${vmKeys[0]}`);
    
    // The Kotlin code looks for the signer: a function that takes a path string
    // and returns a DIFFERENT string matching /^[A-Za-z0-9_-]{20,200}$/
    const tokenRegex = /^[A-Za-z0-9_-]{20,200}$/;
    const probePath = "/manga/g2rk/chapters";
    
    console.log("\n=== SEARCHING FOR SIGNER FUNCTION ===");
    console.log(`Probe path: "${probePath}"`);
    console.log(`Token regex: ${tokenRegex}\n`);
    
    let signerFound = null;
    let installerFound = null;
    let responseHandler = null;
    
    for (const key of allFnKeys) {
        const fn = vmObj[key];
        
        // Test as signer
        if (!signerFound) {
            try {
                const out = fn(probePath);
                if (typeof out === 'string' && out !== probePath && tokenRegex.test(out)) {
                    signerFound = { key, fn };
                    console.log(`✅ SIGNER FOUND: vmObj.${key}`);
                    console.log(`   Input:  "${probePath}"`);
                    console.log(`   Output: "${out}"`);
                    console.log(`   Token length: ${out.length}`);
                }
            } catch (e) {
                // Not a signer
            }
        }
        
        // Test as installer (axios interceptor installer)
        if (!installerFound) {
            try {
                let got = false;
                let resFn = null;
                const fakeAxios = {
                    interceptors: {
                        request: { use: function() {} },
                        response: { use: function(fn) { got = true; resFn = fn; } }
                    },
                    defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                };
                fn(fakeAxios);
                if (got) {
                    installerFound = { key, fn };
                    responseHandler = resFn;
                    console.log(`\n✅ INSTALLER FOUND: vmObj.${key}`);
                    console.log(`   Response handler captured: ${!!responseHandler}`);
                }
            } catch (e) {
                // Not an installer
            }
        }
        
        if (signerFound && installerFound) break;
    }
    
    if (!signerFound) {
        console.log("\n❌ SIGNER NOT FOUND");
        console.log("\nDumping all function results for analysis:");
        for (const key of allFnKeys) {
            try {
                const out = vmObj[key](probePath);
                const typeStr = typeof out;
                if (typeStr === 'string' && out.length > 0 && out.length < 300) {
                    console.log(`  vmObj.${key} -> string(${out.length}): "${out.substring(0, 100)}"`);
                } else if (typeStr === 'string') {
                    console.log(`  vmObj.${key} -> string(${out.length})`);
                } else if (typeStr === 'number' || typeStr === 'boolean') {
                    console.log(`  vmObj.${key} -> ${typeStr}: ${out}`);
                } else if (out instanceof Promise) {
                    console.log(`  vmObj.${key} -> Promise`);
                } else if (out === undefined) {
                    console.log(`  vmObj.${key} -> undefined`);
                } else if (out === null) {
                    console.log(`  vmObj.${key} -> null`);
                } else {
                    console.log(`  vmObj.${key} -> ${typeStr}`);
                }
            } catch (e) {
                console.log(`  vmObj.${key} -> ERROR: ${e.message.substring(0, 80)}`);
            }
        }
    } else {
        // Test the signer with different paths
        console.log("\n=== TESTING SIGNER WITH DIFFERENT PATHS ===");
        const testPaths = [
            "/manga/g2rk/chapters",
            "/manga/abc123",
            "/chapters/xyz",
            "/search?q=test",
            "/api/v1/manga/g2rk",
        ];
        for (const path of testPaths) {
            try {
                const token = signerFound.fn(path);
                console.log(`  signer("${path}") -> "${token}"`);
            } catch (e) {
                console.log(`  signer("${path}") -> ERROR: ${e.message}`);
            }
        }
        
        // Test full fetchProtected-like flow
        if (responseHandler) {
            console.log("\n=== TESTING RESPONSE HANDLER ===");
            // Create a fake encrypted response to test decryption
            const fakeEncrypted = { e: "someEncryptedData", iv: "someIV" };
            const fakeResp = {
                data: fakeEncrypted,
                status: 200,
                statusText: 'OK',
                headers: {},
                config: { url: '/manga/g2rk/chapters?_=token', method: 'get', baseURL: '/api/v1' },
                request: {}
            };
            try {
                const decoded = responseHandler(fakeResp);
                if (decoded instanceof Promise) {
                    decoded.then(r => console.log("  Response handler result:", JSON.stringify(r).substring(0, 200)))
                           .catch(e => console.log("  Response handler error:", e.message));
                } else {
                    console.log("  Response handler result:", JSON.stringify(decoded).substring(0, 200));
                }
            } catch (e) {
                console.log("  Response handler error:", e.message);
            }
        }
    }

    // Wait a bit for async operations
    setTimeout(() => {
        console.log("\n=== DONE ===");
        process.exit(0);
    }, 3000);

} catch (err) {
    console.error("Execution failed:", err.message);
    console.error(err.stack);
}
