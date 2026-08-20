# Task 4: Mobile & UX (Progressive Web App - PWA)

Questo documento descrive la specifica funzionale e l'architettura tecnica per trasformare la dashboard in una **Progressive Web App (PWA)** mobile-first, veloce, reattiva e con supporto offline:
1. **PWA Manifest & Iconografia Multi-Dispositivo (iOS / Android / Desktop)**
2. **Service Worker & Strategie di Caching Intelligente (Cache-First + Network-First)**
3. **Mobile-First UX: Bottom Navigation Bar, Touch Gestures & Tastiera Numerica Nativa**
4. **Haptic Feedback & Micro-animazioni di Stato**
5. **Installabilità Standalone (Zero-Browser Frame)**

---

## 1. Obiettivi e Funzionalità

### 1.1 Installazione Standalone (PWA)
- L'applicazione può essere installata come un'app nativa:
  - Su **iOS Safari**: tramite banner o istruzioni *"Aggiungi alla schermata Home"* $\rightarrow$ si apre a schermo intero senza barre URL di Safari (`display: standalone`).
  - Su **Android Chrome**: tramite prompt nativo di installazione (`beforeinstallprompt` event).
  - Su **Desktop (Chrome/Edge/Brave/macOS)**: icona di installazione app nella barra degli indirizzi.
- Splash screen personalizzato con tema scuro e logo Garmin/Renpho.
- Gestione della barra di stato del telefono (`apple-mobile-web-app-status-bar-style: black-translucent`, `theme-color: #0f172a`).

### 1.2 Supporto Offline e Caching (Service Worker)
- **Asset Statici (HTML, CSS, JS, Icone, Font, Chart.js)**: Caching aggressivo con strategia **Cache-First (Stale-While-Revalidate)**.
  - L'app si carica istantaneamente in meno di 100ms anche su connessioni cellulari lente in palestra o in modalità aereo.
- **Dati API (Allenamenti, Peso, Zone Cardio)**: Strategia **Network-First con Fallback su IndexedDB / Cache Storage**.
  - Quando lo smartphone è online, mostra sempre gli ultimi dati aggiornati dal server.
  - Quando si è offline o con segnale debole nello spogliatoio, mostra l'ultima sessione memorizzata in cache senza bloccare la UI con messaggi di errore di rete.

### 1.3 UI Mobile-First e Touch Optimization
- **Bottom Navigation Bar per Smartphone**:
  - Sui dispositivi mobili ($\le 768px$), la barra di navigazione superiore si sposta in basso con icone touch-friendly ad altezza pollice (Dashboard, Allenamento, Composizione, Zone Cardio, Impostazioni).
- **Tastiera e Input Ottimizzati per il Fitness**:
  - Sugli input di modifica carico e ripetizioni: `inputmode="decimal"` o `inputmode="numeric"`, `step="0.5"`, con pulsanti rapidi incremento/decremento rapidi (*+1.25 kg, +2.5 kg, +5 kg*).
  - Evita lo zoom accidentale su iOS impostando `font-size: 16px` minimo sui campi input.
- **Haptic Feedback (Vibrazione)**:
  - Se supportato dal browser (`navigator.vibrate`), vibrazione leggera di conferma (40ms) quando si salva una serie o si batte un nuovo record personale.
- **Pull-to-Refresh & Swipe Navigation**:
  - Possibilità di scorrere verso il basso per attivare la sincronizzazione con Garmin e Renpho.
  - Transizioni laterali fluide (Slide transition) quando si cambia scheda.

---

## 2. Dettagli di Implementazione

### 2.1 File `manifest.webmanifest` (in `src/main/resources/static/`)
```json
{
  "name": "Garmin & Renpho Fitness Dashboard",
  "short_name": "GarminFit",
  "description": "Dashboard avanzata per tracciamento allenamento pesi, corsa e composizione corporea",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#0f172a",
  "theme_color": "#0f172a",
  "orientation": "portrait-primary",
  "icons": [
    {
      "src": "/icons/icon-192x192.png",
      "sizes": "192x192",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/icons/icon-512x512.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/icons/apple-touch-icon.png",
      "sizes": "180x180",
      "type": "image/png"
    }
  ]
}
```

### 2.2 Service Worker `sw.js`
```javascript
const CACHE_NAME = 'garmin-fit-v1';
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  'https://cdn.jsdelivr.net/npm/chart.js',
  'https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Strategia Cache-First per static assets
  if (STATIC_ASSETS.includes(url.pathname) || event.request.destination === 'style' || event.request.destination === 'script') {
    event.respondWith(
      caches.match(event.request).then((cached) => cached || fetch(event.request))
    );
    return;
  }

  // Strategia Network-First con fallback in cache per API
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const clone = response.clone();
          caches.open('api-cache-v1').then((cache) => cache.put(event.request, clone));
          return response;
        })
        .catch(() => caches.match(event.request))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request))
  );
});
```

### 2.3 Regole CSS Mobile-First (`index.html`)
```css
/* Bottom Navigation Bar su Mobile */
@media (max-width: 768px) {
  .nav-header {
    display: none; /* Nasconde la top bar su schermi piccoli */
  }
  
  .bottom-nav {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 64px;
    background: rgba(15, 23, 42, 0.95);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    display: flex;
    justify-content: space-around;
    align-items: center;
    border-top: 1px solid var(--border-color);
    z-index: 1000;
    padding-bottom: env(safe-area-inset-bottom);
  }

  .bottom-nav-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    font-size: 0.72rem;
    color: var(--text-muted);
    text-decoration: none;
    gap: 3px;
    transition: color 0.2s ease;
  }

  .bottom-nav-item.active {
    color: var(--primary);
    font-weight: 600;
  }

  /* Margine inferiore per evitare che la barra copra il contenuto */
  .main-content {
    padding-bottom: calc(75px + env(safe-area-inset-bottom));
  }

  /* Modal touch friendly a pieno schermo su mobile */
  .modal-content {
    width: 95% !important;
    max-height: 90vh;
    border-radius: 16px 16px 0 0;
    margin-top: auto;
  }
}
```

---

## 3. Gestione e Prompt di Installazione PWA

```javascript
let deferredPrompt;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
  const installBanner = document.getElementById('pwaInstallBanner');
  if (installBanner) installBanner.style.display = 'flex';
});

function triggerPwaInstall() {
  if (deferredPrompt) {
    deferredPrompt.prompt();
    deferredPrompt.userChoice.then((choiceResult) => {
      if (choiceResult.outcome === 'accepted') {
        console.log('User accepted PWA installation');
      }
      deferredPrompt = null;
      document.getElementById('pwaInstallBanner').style.display = 'none';
    });
  }
}
```

---

## 4. Esperienza Utente Ottimizzata per la Palestra

1. **Modalità "Durante l'Allenamento"**:
   - Schermo sempre attivo (tramite Screen Wake Lock API: `navigator.wakeLock.request('screen')`) per impedire lo spegnimento del display mentre ci si allena e si registrano i pesi.
2. **Calcolatore Dischi di Caricamento Bilanciere**:
   - Inserendo ad esempio 70 kg, mostra graficamente i dischi da caricare per lato (es. *1x20kg, 1x5kg per lato con bilanciere olimpico da 20kg*).
3. **Timer di Recupero tra le Serie**:
   - Timer integrato (es. 90s / 120s) con suono/vibrazione di fine recupero.
