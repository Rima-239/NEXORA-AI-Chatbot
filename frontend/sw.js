/**
 * NEXORA Service Worker (sw.js)
 * Caches static application shell for instant load and offline UI availability.
 * API endpoints (/api/*) are network-only to preserve real-time AI accuracy.
 */

const CACHE_NAME = 'nexora-v1.0.0';
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/css/nexora.css',
  '/js/app.js',
  '/manifest.json',
  '/assets/icon.svg'
];

// Install Event: Cache app shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(STATIC_ASSETS);
    }).then(() => self.skipWaiting())
  );
});

// Activate Event: Clear outdated caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch Event: Cache-first for static assets, network-only for /api/
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // API calls are strictly live network requests (never serve stale AI responses)
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(fetch(event.request));
    return;
  }

  // App shell and static assets: Cache first, fallback to network
  event.respondWith(
    caches.match(event.request).then((cachedResponse) => {
      if (cachedResponse) {
        return cachedResponse;
      }
      return fetch(event.request).then((networkResponse) => {
        // Cache newly fetched valid static assets
        if (networkResponse && networkResponse.status === 200 && event.request.method === 'GET') {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseToCache);
          });
        }
        return networkResponse;
      }).catch(() => {
        // If offline and requesting an HTML page, fallback to cached index.html
        if (event.request.headers.get('accept')?.includes('text/html')) {
          return caches.match('/index.html');
        }
      });
    })
  );
});
