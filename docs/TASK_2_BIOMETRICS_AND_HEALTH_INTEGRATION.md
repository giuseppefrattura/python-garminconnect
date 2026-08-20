# Task 2: Biometria & Salute Integrata

Questo documento descrive la specifica funzionale e l'architettura tecnica per l'integrazione e la correlazione avanzata tra:
1. **Dati di Composizione Corporea (Renpho)**: Peso, % Massa Grassa (BF), Massa Muscolare Scheletrica (SMM), Massa Magra (FFM), Grasso Viscerale, BMR.
2. **Metriche di Recupero e Salute (Garmin Connect)**: Fasi e Punteggio del Sonno, Body Battery, Variabilità della Frequenza Cardiaca (HRV Status), Livello di Stress giornaliero.
3. **Calcolo della Forza Relativa e degli Indici di Efficienza Atletica**.
4. **Algoritmo di Readiness & Recovery Score (0-100)** per guidare l'intensità dell'allenamento.

---

## 1. Obiettivi e Funzionalità

### 1.1 Sincronizzazione Dati Salute da Garmin
Estensione del proxy Python (`garmin-proxy`) e del servizio Java per raccogliere le metriche fisiologiche fornite da Garmin Connect:
- **Sonno**:
  - Punteggio qualità sonno (0-100) e giudizio (es. *Eccellente, Buono, Scarso*).
  - Durata totale del sonno e ripartizione per fasi: *Sonno Profondo (Deep)*, *Sonno Leggero (Light)*, *Fase REM*, *Veglia (Awake)*.
  - Frequenza respiratoria media durante il sonno.
- **Body Battery**:
  - Valore al risveglio (capacità energetica iniziale).
  - Valore minimo e massimo della giornata.
  - Ricarica totale notturna (+ Punti) vs Scarica diurna (- Punti).
- **HRV Status (Heart Rate Variability)**:
  - Media notturna in ms (rMSSD).
  - Stato HRV: *Bilanciato (Balanced)*, *Basso (Low)*, *Non bilanciato (Unbalanced)*.
  - Baseline di 7 giorni per identificare affaticamento del sistema nervoso simpatico.
- **Stress**:
  - Media giornaliera (0-100) e minuti trascorsi in zona riposo, basso, medio e alto stress.

### 1.2 Correlazione Renpho $\times$ Performance di Forza
- **Forza Relativa (Wilks / DOTS o Rapporto Diretto)**:
  $$\text{Forza Relativa} = \frac{\text{1RM Massimale (kg)}}{\text{Peso Corporeo (kg)}}$$
  $$\text{Forza su Massa Magra} = \frac{\text{1RM Massimale (kg)}}{\text{Fat Free Mass FFM (kg)}}$$
- Permette di tracciare se l'aumento dei carichi in palestra sia dovuto a reale incremento di forza/ipertrofia o a un aumento di peso corporeo/massa grassa.
- Tracciamento della **Ricostituzione Corporea**: grafici che correlano la perdita di grasso (%) con l'andamento del tonnellaggio sollevato.

### 1.3 Readiness & Recovery Score Giornaliero (Algoritmo di Guida all'Allenamento)
Il sistema calcola ogni mattina alle 06:00 (o ad ogni sincronizzazione) un indice di **Readiness (0 - 100)** basato su un modello ponderato:
$$\text{Readiness} = 0.35 \times S_{\text{Sonno}} + 0.30 \times S_{\text{BodyBattery}} + 0.20 \times S_{\text{HRV}} + 0.15 \times S_{\text{CaricoRecente}}$$

- **Score 80-100 (Verde - Ottimale)**: Recupero completo. Ideale per sessioni ad alta intensità, tentativi di nuovi PR o alto volume.
- **Score 50-79 (Giallo - Moderato)**: Recupero standard. Allenamento regolare programmato.
- **Score < 50 (Rosso - Affaticato)**: Recupero insufficiente o stress sistemico elevato. Suggerimento di sessione di scarico (deload), cardio leggero in Zona 1/2 o riposo.

---

## 2. Modello Dati e Database (Liquibase)

### Tabella `daily_health_metrics`
```yaml
databaseChangeLog:
  - changeSet:
      id: 008-create-daily-health-metrics
      author: giuseppe
      changes:
        - createTable:
            tableName: daily_health_metrics
            columns:
              - column:
                  name: date
                  type: DATE
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: sleep_score
                  type: INTEGER
              - column:
                  name: sleep_duration_seconds
                  type: INTEGER
              - column:
                  name: deep_sleep_seconds
                  type: INTEGER
              - column:
                  name: light_sleep_seconds
                  type: INTEGER
              - column:
                  name: rem_sleep_seconds
                  type: INTEGER
              - column:
                  name: awake_seconds
                  type: INTEGER
              - column:
                  name: body_battery_wake
                  type: INTEGER
              - column:
                  name: body_battery_max
                  type: INTEGER
              - column:
                  name: body_battery_min
                  type: INTEGER
              - column:
                  name: hrv_nightly_avg
                  type: DOUBLE PRECISION
              - column:
                  name: hrv_status
                  type: VARCHAR(30) # BALANCED, LOW, UNBALANCED, POOR
              - column:
                  name: avg_stress_level
                  type: INTEGER
              - column:
                  name: readiness_score
                  type: INTEGER
              - column:
                  name: readiness_advice
                  type: VARCHAR(255)
              - column:
                  name: created_at
                  type: TIMESTAMP
```

---

## 3. Componenti Architetturali

### 3.1 Estensione Proxy Python (`garmin-proxy/main.py`)
Aggiunta dei metodi wrapper che interrogano `garminconnect`:
- `GET /health/sleep?date=YYYY-MM-DD` $\rightarrow$ `client.get_sleep_data(date)`
- `GET /health/body-battery?date=YYYY-MM-DD` $\rightarrow$ `client.get_body_battery(date)`
- `GET /health/hrv?date=YYYY-MM-DD` $\rightarrow$ `client.get_hrv_data(date)`
- `GET /health/daily-summary?date=YYYY-MM-DD` $\rightarrow$ raggruppa tutti i dati di salute con una singola chiamata aggregata.

### 3.2 Servizi Java (`garmin-service`)
1. **`GarminHealthSyncService.java`**:
   - Esegue il fetch giornaliero delle metriche di salute e le salva in `DailyHealthMetric`.
2. **`ReadinessCalculationService.java`**:
   - Calcola il punteggio di readiness normalizzando i parametri fisiologici (Sonno, Body Battery, HRV vs baseline a 7gg) e il carico acuto degli ultimi 3 giorni.
3. **`BiometricsAnalyticsService.java`**:
   - Effettua il merge tra i dati di peso/composizione corporea (da `RenphoService`) e i dati di forza (da `StrengthWorkoutService`).
   - Calcola i trend di forza relativa ($1RM / \text{BW}$) nel tempo.

---

## 4. API Endpoints

- `GET /api/health/daily?date=YYYY-MM-DD`: Dettaglio completo salute e sonno per la giornata selezionata.
- `GET /api/health/readiness`: Punteggio di readiness e consiglio del giorno.
- `GET /api/analytics/relative-strength`: Serie storica della forza relativa per i principali esercizi.
- `GET /api/analytics/body-composition-vs-performance`: Correlazione grafica tra variazione del peso corporeo, massa grassa e tonnellaggio medio per sessione.

---

## 5. Mockup & Componenti UI

1. **Widget "Daily Readiness & Recupero"**:
   - Posizionato in cima alla dashboard con gauge ad anello colorato (0-100).
   - Badge descrittivo: *"Recupero Ottimale (87/100) - Oggi sei pronto per spingere sul petto e cercare nuovi carichi!"*.
2. **Scheda Sonno & Body Battery**:
   - Breakdown visivo delle fasi di sonno (grafico a barre orizzontali stratificate: Deep, Light, REM).
   - Grafico lineare del Body Battery nelle 24 ore (ricarica notturna vs scarica in allenamento).
3. **Grafico a Doppio Asse Peso vs 1RM**:
   - Asse sinistro (kg corpo / % grasso), Asse destro (1RM Panca / Squat). Evidenzia i periodi di *cut* (definizione) o *bulk* (massa) e il loro impatto sulla forza reale.
