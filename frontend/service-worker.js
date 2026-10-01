const CACHE_NAME = 'butterfly-ai-v1.2';
const STATIC_ASSETS = [
    './',
    './index.html',
    './style.css',
    './script.js',
    './manifest.json',
    './vendor/lucide.min.js',
    './vendor/marked.min.js',
    './butterfly-favicon.svg',
    './favicon.ico',
    './favicon-16x16.png',
    './favicon-32x32.png',
    './apple-touch-icon.png',
    './icon-192.png',
    './icon-512.png',
    './icon-48.png',
    './logo.png'
];

// Install: Pre-cache static shell assets
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => {
            console.log('[PWA SW] Pre-caching offline shell assets');
            return cache.addAll(STATIC_ASSETS);
        }).then(() => self.skipWaiting())
    );
});

// Activate: Clean up previous cache versions
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames.map((name) => {
                    if (name !== CACHE_NAME) {
                        console.log('[PWA SW] Removing obsolete cache:', name);
                        return caches.delete(name);
                    }
                })
            );
        }).then(() => self.clients.claim())
    );
});

// Fetch: Caching & offline strategy
self.addEventListener('fetch', (event) => {
    const request = event.request;
    const url = new URL(request.url);

    // Skip non-GET requests or non-HTTP protocols (e.g. chrome-extension://)
    if (request.method !== 'GET' || !url.protocol.startsWith('http')) {
        return;
    }

    // Dynamic API endpoints, audio streaming, and downloads: Network-first/Network-only with graceful offline fallback
    if (url.pathname.startsWith('/api/') || 
        url.pathname.startsWith('/audio/') || 
        url.pathname.startsWith('/downloads/') ||
        url.pathname === '/health' ||
        url.pathname === '/chat' ||
        url.pathname.startsWith('/conversation/')) {
        event.respondWith(
            fetch(request).catch(() => {
                return new Response(JSON.stringify({
                    error: 'You are currently offline. Connect to the internet for live AI features.',
                    offline: true
                }), {
                    status: 503,
                    headers: { 'Content-Type': 'application/json' }
                });
            })
        );
        return;
    }

    // Navigation requests (HTML pages): Return cached index.html when offline
    if (request.mode === 'navigate') {
        event.respondWith(
            fetch(request).catch(() => {
                return caches.match('./index.html') || caches.match('/');
            })
        );
        return;
    }

    // Static assets: Stale-While-Revalidate pattern for fast loading with background refresh
    event.respondWith(
        caches.match(request).then((cachedResponse) => {
            const fetchPromise = fetch(request).then((networkResponse) => {
                if (networkResponse && networkResponse.status === 200 && networkResponse.type === 'basic') {
                    const responseToCache = networkResponse.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(request, responseToCache);
                    });
                }
                return networkResponse;
            }).catch(() => {
                // Network failed, nothing to revalidate
            });

            return cachedResponse || fetchPromise;
        })
    );
});
