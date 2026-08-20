# Task 3: Sicurezza Web & Hardening per Esposizione su Internet

Questo documento descrive la specifica tecnica completa, i requisiti di sicurezza e il piano di hardening per esporre in sicurezza l'applicazione su Internet (tramite Dominio Pubblico, Reverse Proxy con HTTPS o Cloudflare Tunnel / Tailscale).

---

## 1. Analisi del Rischio e Obiettivi di Sicurezza

Quando l'applicazione viene esposta su Internet, deve essere protetta contro i principali vettori di attacco definiti dallo standard **OWASP Top 10**:

1. **Brute Force & Credential Stuffing**: Attacchi automatizzati sul form di login e sugli endpoint REST.
2. **Esposizione Non Autorizzata di Servizi Interni**: PostgreSQL, Proxy FastAPI e Renpho Service non devono essere accessibili direttamente dall'esterno.
3. **Intercettazione del Traffico (Man-in-the-Middle)**: Necessità di cifratura TLS obbligatoria (HTTPS) e cookie sicuri (`HttpOnly`, `Secure`, `SameSite=Strict`).
4. **Session Hijacking & CSRF**: Protezione dei token di sessione, prevenzione session fixation e gestione del timeout di inattività.
5. **Denial of Service (DoS) & Resource Exhaustion**: Abuso delle operazioni pesanti di sincronizzazione (`/sync`, `/health/sync`, `/renpho/sync`).
6. **Data Leakage & Logging Insicuro**: Protezione dei token di autenticazione Garmin Connect e delle credenziali salvate su disco o nei log.

---

## 2. Architettura di Sicurezza "Defense in Depth"

L'architettura di sicurezza è strutturata su 5 livelli concentrici:

```mermaid
graph TD
    User["🌐 Utente / Client Web"] -->|HTTPS (TLS 1.3)| Cloudflare["Cloudflare / Reverse Proxy (Nginx/Caddy)"]
    Cloudflare -->|WAF, Rate Limiting & SSL Termination| SecurityHeaders["HTTP Security Headers (HSTS, CSP, etc.)"]
    SecurityHeaders -->|Reverse Proxy| SpringBoot["🛡️ Garmin Service (Spring Boot 3.4.x)"]
    
    subgraph Spring Boot Security Layer
        Filter["Spring Security Filter Chain"]
        RateLimit["Bucket4j / IP Brute Force Filter"]
        AuthMFA["BCrypt + MFA / TOTP Authenticator"]
        SessionMgr["Session Management (Secure, HttpOnly, SameSite)"]
        AuditLog["Security Audit Logger"]
    end
    
    SpringBoot --> Filter
    Filter --> RateLimit
    RateLimit --> AuthMFA
    AuthMFA --> SessionMgr
    
    subgraph Isolated Docker Internal Network
        SpringBoot -->|Internal HTTP| GarminProxy["Garmin Proxy (FastAPI)"]
        SpringBoot -->|Internal HTTP| RenphoService["Renpho Service (FastAPI)"]
        SpringBoot -->|Encrypted JDBC| Postgres["PostgreSQL DB (Port 5432 Closed Externally)"]
    end
```

---

## 3. Specifiche Funzionali & Tecniche

### 3.1 Isolamento delle Reti Docker & Binding delle Porte
Nel file `docker-compose.yml`, nessun database o microservizio di backend ausiliario deve esporre porte aperte su `0.0.0.0`:
- **PostgreSQL (`5432`)**: Nessuna porta pubblicata (`ports:`) verso l'host pubblico; solo comunicazione sulla rete interna Docker `backend-network`.
- **`garmin-proxy` (`8000`)**: Accessibile solo internamente dal container `garmin-service`.
- **`renpho-service` (`8082`)**: Accessibile solo internamente dal container `garmin-service`.
- **`garmin-service` (`8081`)**: Binding esclusivo su `127.0.0.1:8081` (per essere instradato solo dal Reverse Proxy locale o Cloudflare Tunnel) oppure tramite porta `443` con TLS su Nginx.

### 3.2 Autenticazione Avanzata & BCrypt
- Rimozione di tutte le password in chiaro predefinite (`admin/admin`) nei file di configurazione.
- Memorizzazione delle credenziali amministrative tramite tabella database `users` con hash **BCrypt (Strength: 12)**:
  ```sql
  CREATE TABLE users (
      id BIGSERIAL PRIMARY KEY,
      username VARCHAR(50) UNIQUE NOT NULL,
      password_hash VARCHAR(255) NOT NULL,
      role VARCHAR(30) NOT NULL,
      totp_secret VARCHAR(64),
      totp_enabled BOOLEAN DEFAULT FALSE,
      failed_login_attempts INT DEFAULT 0,
      lockout_until TIMESTAMP,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
  ```
- **Politica di Blocco Temporaneo (Account Lockout)**:
  - Dopo 5 tentativi consecutivi di login falliti entro 10 minuti, l'account viene bloccato per 15 minuti.
  - Reset del contatore dei tentativi falliti ad ogni login riuscito.

### 3.3 Autenticazione a Due Fattori (MFA / 2FA con TOTP)
- Integrazione dello standard RFC 6238 (**TOTP - Time-Based One-Time Password**) compatibile con Google Authenticator, Authy e 1Password.
- **Flusso di Attivazione 2FA**:
  1. L'utente accede alla sezione "Impostazioni Sicurezza" nella dashboard.
  2. Il backend genera un segreto Base32 e restituisce un QR Code URI (`otpauth://totp/GarminAnalytics:admin?secret=...`).
  3. L'utente scansiona il QR Code e inserisce un codice a 6 cifre per confermare e attivare la 2FA.
- **Flusso di Login 2FA**:
  1. Inserimento di Username e Password.
  2. Se la 2FA è attiva, reindirizzamento alla schermata di verifica codice OTP a 6 cifre prima di emettere il cookie di sessione.

### 3.4 Rate Limiting & Protezione Anti-Abuso (Bucket4j)
Limitazione della frequenza delle richieste a livello di Spring Security Filter per prevenire DoS e brute force:
- **Endpoint `/login`**: Massimo 10 richieste al minuto per IP.
- **Endpoint `/api/sync`, `/api/health/sync`, `/api/renpho/sync`**: Massimo 3 chiamate ogni 5 minuti per utente (poiché le sincronizzazioni con i server Garmin/Renpho sono onerose).
- **Endpoint di Lettura Dati (`/api/**`)**: Massimo 60 richieste al minuto per IP.
- Risposta HTTP standard `429 Too Many Requests` con header `Retry-After: <secondi>` in caso di superamento dei limiti.

### 3.5 Cookie di Sessione & Header HTTP di Sicurezza
Configurazione in `SecurityConfig.java`:
```java
http.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
    .sessionFixation().migrateSession()
    .maximumSessions(2)
    .maxSessionsPreventsLogin(false)
);

http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:;")
    )
    .frameOptions(frame -> frame.deny())
    .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
    .contentTypeOptions(Customizer.withDefaults())
    .httpStrictTransportSecurity(hsts -> hsts
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
);
```

Attributi dei Cookie di Sessione (`application.yml`):
```yaml
server:
  servlet:
    session:
      cookie:
        http-only: true
        secure: true
        same-site: strict
        max-age: 1800 # 30 minuti
```

### 3.6 Gestione e Protezione dei Token Garmin Connect
- I file di sessione OAuth salvati in `~/.garminconnect` contengono token JWT/Garth con accesso completo all'account Garmin dell'utente.
- **Misure di Hardening**:
  - Permessi restrittivi `chmod 600` sui file di token all'interno del container Docker.
  - Esecuzione dei container con utente non root (`appuser` con UID 1000).
  - Variabile `GARMIN_API_KEY` obbligatoria e robusta per tutte le comunicazioni tra servizi interni.
  - Nessun token o dato sensibile registrato nei log dell'applicazione (mascheramento regex nei logback appenders).

### 3.7 Security Audit Log (Monitoraggio Accessi)
Creazione di un servizio dedicato `SecurityAuditService` che registra su file separato o tabella database:
- `TIMESTAMP`: Data e ora dell'evento.
- `EVENT_TYPE`: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`, `MFA_ENABLED`, `SYNC_TRIGGERED`, `UNAUTHORIZED_ACCESS`.
- `IP_ADDRESS`: Indirizzo IP del client (estratto correttamente tramite header `X-Forwarded-For`).
- `USER_AGENT`: Browser e dispositivo utilizzato.
- `DETAILS`: Dettagli dell'operazione.

---

## 4. Piano di Rilascio e Opzioni di Distribuzione su Internet

### Opzione A: Cloudflare Tunnel (Raccomandata - Nessuna porta aperta sul router)
- Nessuna necessità di aprire porte `80`/`443` sul modem di casa o IP statico.
- Protezione automatica DDoS di Cloudflare, TLS 1.3 automatico e Cloudflare Access (WAF / Geo-blocking opzionale).
- Container `cloudflared` affiancato nel `docker-compose.yml`.

### Opzione B: Reverse Proxy Nginx / Caddy con Let's Encrypt
- Configurazione Nginx con certificato SSL automatico Let's Encrypt (Certbot).
- Inoltro delle richieste verso `http://127.0.0.1:8081`.

---

## 5. Criteri di Accettazione e Test di Sicurezza

- [ ] Nessun servizio secondario (Postgres, Python Proxies) esposto su porte pubbliche `0.0.0.0`.
- [ ] Cambio password amministratore con hashing BCrypt e rimozione credenziali hardcoded.
- [ ] 2FA / TOTP perfettamente funzionante tramite scansione QR Code ed inserimento OTP.
- [ ] Rate limiting attivo: superamento soglia tentativi login genera `HTTP 429`.
- [ ] Audit log attivo con registrazione degli IP e tentativi di accesso.
- [ ] Header di sicurezza (HSTS, CSP, X-Frame-Options, SameSite Cookie) verificabili tramite scanner (es. `securityheaders.com` con valutazione A+).
