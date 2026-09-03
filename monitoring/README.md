# Monitoraggio Grafana Cloud con Grafana Alloy

Questa directory contiene l'infrastruttura completa di osservabilità per l'ecosistema **Garmin & Renpho**, progettata per inviare metriche e log a **Grafana Cloud** in tempo reale.

---

## 🏗️ Architettura

- **Grafana Alloy**: Collector unificato e leggero di Grafana Labs che gira come container Docker in `docker-compose.prod.yml`.
- **Prometheus Metrics (remote_write)**:
  - `garmin-service` (Spring Boot 3 + Micrometer su `/actuator/prometheus`, protetto da Bearer Token).
  - `garmin-proxy` (FastAPI + Prometheus Instrumentator su `/metrics`).
  - `renpho-service` (FastAPI + Prometheus Instrumentator su `/metrics`).
- **Loki Logs (logs_write)**:
  - Cattura in streaming dei log di tutti i container Docker tramite `/var/run/docker.sock` con etichette automatiche (`container`, `service_name`, `job`).

---

## 🔑 Configurazione delle Variabili d'Ambiente

Nel file `.env` del tuo server di produzione, inserisci i parametri forniti dal portale di **Grafana Cloud**:

```dotenv
# ==============================================================================
# GRAFANA CLOUD OBSERVABILITY
# ==============================================================================
# Endpoint Prometheus remote_write di Grafana Cloud (visibile su Grafana Cloud -> Prometheus -> Details)
GRAFANA_CLOUD_PROMETHEUS_URL=https://prometheus-prod-XX-prod-XX.grafana.net/api/prom/push
GRAFANA_CLOUD_PROMETHEUS_USER=123456

# Endpoint Loki push di Grafana Cloud (visibile su Grafana Cloud -> Hosted Logs -> Details)
GRAFANA_CLOUD_LOKI_URL=https://logs-prod-XX.grafana.net/loki/api/v1/push
GRAFANA_CLOUD_LOKI_USER=654321

# Access Policy Token di Grafana Cloud con permessi "metrics:write" e "logs:write"
GRAFANA_CLOUD_TOKEN=glc_eyJ...

# Token segreto per autorizzare Grafana Alloy a fare scrape di /actuator/prometheus
METRICS_BEARER_TOKEN=genera-un-token-segreto-casuale-qui
```

---

## 📊 Dashboard Grafana Pronti all'Uso

Troverai due template JSON pronti nella cartella `monitoring/dashboards/`:

1. **`fitness-biometrics-hub.json`** (**Fitness & Biometrics Hub**):
   - **Training Readiness Score** con gauge colorato dinamico (0-100).
   - **Sonno**: Punteggio qualità e durata totale.
   - **Biometria**: HRV Notturno medio (ms) e Livello di Stress giornaliero.
   - **Composizione Corporea Renpho**: Peso (kg), % Grasso corporeo, Massa muscolare.
   - **Allenamento Forza**: Volume ultimo allenamento (kg), serie completate e Record Personali (PRs).
   - **Sincronizzazione**: Esito ultimo Midnight Sync (SUCCESS / PARTIAL / FAILED), durata e orario esecuzione.

2. **`system-security-devops.json`** (**System & Security DevOps**):
   - **Traffico HTTP**: Richieste al secondo (RPS), Latenze p95 e p99 per endpoint, codici di stato (2xx, 4xx, 5xx).
   - **Runtime JVM (Spring Boot)**: Heap Memory usata vs max, pause del Garbage Collector, thread attivi.
   - **Database Connection Pool (HikariCP)**: Connessioni PostgreSQL attive, idle e in attesa.
   - **Sicurezza**: Richieste bloccate dal Rate Limiter (HTTP 429) suddivise per categoria (`login`, `sync`), tentativi di login falliti.
   - **Streaming Log Docker**: Pannello log live interrogabile tramite Grafana Loki con filtri per container e livello (`ERROR`, `WARN`, `INFO`).

### Come Importare i Dashboard su Grafana Cloud:
1. Accedi alla tua istanza Grafana Cloud.
2. Dal menu laterale clicca su **Dashboards** -> **New** -> **Import**.
3. Trascina o incolla il contenuto del file JSON desiderato (`fitness-biometrics-hub.json` o `system-security-devops.json`).
4. Seleziona i tuoi datasource di Grafana Cloud (Prometheus e/o Loki) quando richiesto e clicca su **Import**.

---

## 🚀 Avvio in Produzione

Per avviare l'intero stack con il container `alloy`:

```bash
docker compose -f docker-compose.prod.yml up -d
```

Per verificare lo stato del container Alloy e i log di spedizione:

```bash
docker logs -f alloy
```
