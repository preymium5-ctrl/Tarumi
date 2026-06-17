const fs = require('fs');
const https = require('https');
const { URL } = require('url');

process.on('uncaughtException', (err) => {});
process.on('unhandledRejection', (reason) => {});

// ---- Minimal DOM Mocks ----
class MockCanvas {
    constructor(w,h){this.width=w||300;this.height=h||150;this.style={};}
    getContext(type){if(type==='2d')return{fillRect:()=>{},clearRect:()=>{},getImageData:(x,y,w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),putImageData:()=>{},createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),setTransform:()=>{},drawImage:()=>{},save:()=>{},fillText:()=>{},restore:()=>{},beginPath:()=>{},moveTo:()=>{},lineTo:()=>{},closePath:()=>{},stroke:()=>{},translate:()=>{},scale:()=>{},rotate:()=>{},arc:()=>{},fill:()=>{},measureText:t=>({width:t.length*7}),transform:()=>{},font:'',textBaseline:'',textAlign:'',fillStyle:'',strokeStyle:'',globalAlpha:1,globalCompositeOperation:'source-over',canvas:this};if(type==='webgl'||type==='webgl2')return{getExtension:()=>null,getParameter:p=>p===37446?'Intel':p===37445?'Google Inc.':'',getSupportedExtensions:()=>[]};return null;}
    toDataURL(){return'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==';}toBlob(cb){cb(new Blob());}setAttribute(){}getAttribute(){return null;}
}
class MockImageData{constructor(w,h){if(w instanceof Uint8ClampedArray){this.data=w;this.width=h;this.height=arguments[2]||1;}else{this.width=w||1;this.height=h||1;this.data=new Uint8ClampedArray(this.width*this.height*4);}}}
global.ImageData=MockImageData;

const cfgContent='ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
const metaMock={name:'cfg',content:cfgContent,getAttribute(a){return a==='name'?'cfg':a==='content'?cfgContent:null;}};
const documentMock={documentElement:{outerHTML:'<html></html>',innerText:'',style:{}},body:{innerText:'',appendChild:()=>{},removeChild:()=>{},style:{}},title:'',hidden:false,visibilityState:'visible',addEventListener:()=>{},removeEventListener:()=>{},getElementById:()=>null,querySelector:s=>s==='[disable-devtool-auto]'?null:s==='meta[name="cfg"]'?metaMock:null,querySelectorAll:s=>s==='meta'?[metaMock]:[],getElementsByTagName:t=>t==='meta'?[metaMock]:[],createElement:tag=>{if(tag==='canvas')return new MockCanvas();return{style:{},setAttribute:()=>{},getAttribute:()=>null,appendChild:()=>{},removeChild:()=>{},addEventListener:()=>{},removeEventListener:()=>{},classList:{add:()=>{},remove:()=>{},contains:()=>false},innerHTML:'',innerText:'',textContent:'',tagName:tag.toUpperCase()};},createTextNode:t=>({textContent:t}),cookie:''};

Object.defineProperty(global,'navigator',{value:{userAgent:'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',appCodeName:'Mozilla',platform:'Win32',language:'en-US',languages:['en-US','en'],cookieEnabled:true,hardwareConcurrency:8,maxTouchPoints:0,vendor:'Google Inc.',plugins:{length:0},mimeTypes:{length:0}},configurable:true,writable:true});
Object.defineProperty(global,'location',{value:{href:'https://comix.to/',protocol:'https:',hostname:'comix.to',host:'comix.to',origin:'https://comix.to',pathname:'/',search:'',hash:'',port:''},configurable:true,writable:true});
Object.defineProperty(global,'document',{value:documentMock,configurable:true,writable:true});
global.HTMLCanvasElement=MockCanvas;global.CanvasRenderingContext2D=function(){};global.OffscreenCanvasRenderingContext2D=function(){};global.OffscreenCanvas=MockCanvas;global.WebGLRenderingContext=function(){};global.WebGL2RenderingContext=function(){};global.createImageBitmap=async()=>({width:1,height:1,close:()=>{}});global.Document=function(){};global.open=()=>null;global.top=global;global.parent=global;global.innerWidth=1920;global.innerHeight=1080;global.screen={width:1920,height:1080,availWidth:1920,availHeight:1040,colorDepth:24,pixelDepth:24};global.devicePixelRatio=1;global.performance={now:()=>Date.now(),timing:{navigationStart:Date.now()}};global.requestAnimationFrame=cb=>setTimeout(cb,16);global.cancelAnimationFrame=id=>clearTimeout(id);global.MutationObserver=class{constructor(){}observe(){}disconnect(){}};global.ResizeObserver=class{constructor(){}observe(){}disconnect(){}};global.IntersectionObserver=class{constructor(){}observe(){}disconnect(){}};global.localStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}};global.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}};global.Blob=class{constructor(){this.size=0;this.type='';}};global.URL={createObjectURL:()=>'blob:null',revokeObjectURL:()=>{}};
global.window=new Proxy(global,{get(t,p){if(p==='document')return documentMock;return t[p];},set(t,p,v){t[p]=v;return true;}});
global.fetch=async()=>{throw new Error('fetch not available');};

// ---- Run secure.js ----
const code = fs.readFileSync('secure.js', 'utf8');
const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
const runFn = new Function('window', 'globalThis', 'document', cleanCode);
runFn(global.window, global.window, documentMock);

const vmObj = global.vmU_a0e368;
const signer = global.Ri[0]; // The signer function!
const signerV2 = global.Ri[2]; // Alternative signer

// Helper to make HTTPS request
function httpsGet(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const req = https.request({
            hostname: parsed.hostname,
            port: 443,
            path: parsed.pathname + parsed.search,
            method: 'GET',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                ...headers,
            }
        }, res => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ status: res.statusCode, body: data, headers: res.headers }));
        });
        req.on('error', reject);
        req.end();
    });
}

(async () => {
    console.log("=== SIGNER FUNCTION TESTS ===\n");
    
    const testPaths = [
        "/manga/g2rk/chapters",
        "/manga/g2rk",
        "/chapters/abc123",
        "/search",
    ];
    
    for (const path of testPaths) {
        const token0 = signer(path);
        const token2 = signerV2(path);
        console.log(`Path: "${path}"`);
        console.log(`  Ri[0]: "${token0}" (len=${token0.length})`);
        console.log(`  Ri[2]: "${token2}" (len=${token2.length})`);
    }
    
    // Now let's try actually calling the API with the signed token
    console.log("\n=== TESTING SIGNED API CALLS ===\n");
    
    const apiPath = "/api/v1/manga/g2rk/chapters";
    const signable = "/manga/g2rk/chapters"; // stripped /api/v1
    
    const token = signer(signable);
    const signedUrl = `https://comix.to${apiPath}?_=${encodeURIComponent(token)}`;
    
    console.log(`API path: ${apiPath}`);
    console.log(`Signable: ${signable}`);
    console.log(`Token: ${token}`);
    console.log(`Signed URL: ${signedUrl}`);
    
    try {
        const resp = await httpsGet(signedUrl);
        console.log(`\nResponse status: ${resp.status}`);
        console.log(`Response body (first 500 chars): ${resp.body.substring(0, 500)}`);
        
        if (resp.status === 200) {
            const json = JSON.parse(resp.body);
            if (json.e) {
                console.log("\n*** Response is ENCRYPTED! ***");
                console.log(`Encrypted data keys: ${Object.keys(json).join(', ')}`);
                console.log(`Encrypted 'e' length: ${json.e.length}`);
                
                // Try the response handler (installer/decryptor)
                console.log("\n=== TESTING DECRYPTION ===");
                
                // First, install the interceptor
                let resHandler = null;
                const fakeAxios = {
                    interceptors: {
                        request: { use: function() {} },
                        response: { use: function(fn) { resHandler = fn; } }
                    },
                    defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                };
                vmObj.ro(fakeAxios);
                
                if (resHandler) {
                    console.log("Response handler captured!");
                    const fakeResp = {
                        data: json,
                        status: resp.status,
                        statusText: 'OK',
                        headers: resp.headers,
                        config: { url: signedUrl, method: 'get', baseURL: '/api/v1' },
                        request: {}
                    };
                    try {
                        const decoded = await resHandler(fakeResp);
                        console.log("\nDecrypted result type:", typeof decoded);
                        if (decoded && decoded.data) {
                            const dataStr = JSON.stringify(decoded.data);
                            console.log(`Decrypted data (first 1000 chars): ${dataStr.substring(0, 1000)}`);
                        } else {
                            console.log("Decoded:", JSON.stringify(decoded).substring(0, 500));
                        }
                    } catch(e) {
                        console.log("Decryption error:", e.message);
                        console.log(e.stack);
                    }
                } else {
                    console.log("Failed to capture response handler");
                }
            } else {
                console.log("\nResponse is NOT encrypted (plain JSON)");
                console.log("Data:", JSON.stringify(json).substring(0, 500));
            }
        }
    } catch(e) {
        console.log(`Error: ${e.message}`);
    }
    
    // Also try with token from signerV2
    console.log("\n--- Testing with Ri[2] signer ---");
    const token2 = signerV2(signable);
    const signedUrl2 = `https://comix.to${apiPath}?_=${encodeURIComponent(token2)}`;
    console.log(`Token2: ${token2}`);
    
    try {
        const resp2 = await httpsGet(signedUrl2);
        console.log(`Response status: ${resp2.status}`);
        console.log(`Response body (first 300 chars): ${resp2.body.substring(0, 300)}`);
    } catch(e) {
        console.log(`Error: ${e.message}`);
    }
    
    console.log("\n=== DONE ===");
    process.exit(0);
})();
