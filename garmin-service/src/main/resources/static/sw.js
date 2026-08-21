// ============================================================================
// Service Worker for Garmin & Renpho Fitness Dashboard (GarminFit PWA)
// Version: 1.0.0
// ============================================================================

const STATIC_CACHE_NAME = 'garminfit-static-v1';
const API_CACHE_NAME = 'garminfit-api-v1';

const STATIC_ASSETS = [
    '/',
    '/index.html',
    '/manifest.webmanifest',
    '/favicon.ico',
    '/favicon.png',
    '/icons/icon-192x192.png',
    '/icons/icon-512x512.png',
    '/icons/apple-touch-icon.png',
    'https://cdn.jsdelivr.net/npm/chart.js',
    'https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap'
];

// --- Install Event ---
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(STATIC_CACHE_NAME).then((cache) => {
            return cache.addAll(STATIC_ASSETS).catch((err) => {
                console.warn('[SW] Some static assets failed to pre-cache:', err);
            });
        })
    );
    self.skipWaiting();
});

// --- Activate Event ---
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) => {
            return Promise.all(
                keys.map((key) => {
                    if (key !== STATIC_CACHE_NAME && key !== API_CACHE_NAME) {
                        console.log('[SW] Deleting old cache:', key);
                        return caches.delete(key);
                    }
                })
            );
        })
    );
    self.clients.claim();
});

// --- Fetch Event Handler ---
self.addEventListener('fetch', (event) => {
    const request = event.request;
    const url = new URL(request.url);

    // Only handle GET requests
    if (request.method !== 'GET') {
        return;
    }

    // 1. API Endpoints: Network-First with Cache Fallback
    if (url.pathname.startsWith('/api/')) {
        event.respondWith(
            fetch(request)
                .then((networkResponse) => {
                    if (networkResponse && networkResponse.status === 200) {
                        const clonedResponse = networkResponse.clone();
                        caches.open(API_CACHE_NAME).then((cache) => {
                            cache.put(request, clonedResponse);
                        });
                    }
                    return networkResponse;
                })
                .catch(async () => {
                    // Network failed (offline) - Try to serve from API cache
                    const cachedResponse = await caches.match(request);
                    if (cachedResponse) {
                        return cachedResponse;
                    }
                    return new Response(JSON.stringify({
                        status: 'offline',
                        message: 'Dispositivo offline e nessun dato in cache disponibile.'
                    }), {
                        headers: { 'Content-Type': 'application/json' },
                        status: 503
                    });
                })
        );
        return;
    }

    // 2. Static Assets & Fonts & Scripts: Cache-First / Stale-While-Revalidate
    event.respondWith(
        caches.match(request).then((cachedResponse) => {
            const fetchPromise = fetch(request).then((networkResponse) => {
                if (networkResponse && networkResponse.status === 200) {
                    const clonedResponse = networkResponse.clone();
                    caches.open(STATIC_CACHE_NAME).then((cache) => {
                        cache.put(request, clonedResponse);
                    });
                }
                return networkResponse;
            }).catch(() => {
                // If offline and not in cache, fallback
                return cachedResponse;
            });

            return cachedResponse || fetchPromise;
        })
    );
});
