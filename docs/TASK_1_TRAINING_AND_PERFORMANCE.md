# Task 1: Allenamento & Performance

Questo documento descrive la specifica funzionale e l'architettura tecnica per l'evoluzione dell'area **Allenamento & Performance**, includendo:
1. **Rilevamento e Notifica Automatica dei Record Personali (PR)**
2. **Progressione e Stima 1RM (One Repetition Max)**
3. **Gestione Piani / Schede di Allenamento (Workout Templates)**
4. **Target di Volume Muscolare Settimanale (MEV, MAV, MRV)**

---

## 1. Obiettivi e Funzionalità

### 1.1 Rilevamento Automatico dei Personal Record (PR)
Ad ogni sincronizzazione o modifica di una serie (tramite `/api/exercise-set`), il sistema analizza i dati ed estrae automaticamente nuovi record per ogni esercizio:
- **Max Weight PR**: Il peso massimo mai sollevato per l'esercizio.
- **Max Volume Set PR**: Il tonnellaggio massimo in una singola serie ($\text{reps} \times \text{peso}$).
- **Estimated 1RM PR**: Il massimale teorico stimato più alto registrato.
- **Max Reps PR**: Il numero massimo di ripetizioni con un dato carico o a corpo libero.

Quando viene battuto un record, viene evidenziato visivamente nella schermata di allenamento (con badge dorato 🏆) e registrato nella cronologia dei record dell'utente.

### 1.2 Formule di Stima 1RM
Per la stima del massimale viene utilizzata la formula combinata di **Brzycki** ed **Epley**:
- Per $\text{reps} = 1$: $\text{1RM} = \text{peso}$
- Per $1 < \text{reps} \le 10$: 
  $$\text{1RM}_{\text{Brzycki}} = \frac{\text{peso}}{1.0278 - (0.0278 \times \text{reps})}$$
- Per $\text{reps} > 10$:
  $$\text{1RM}_{\text{Epley}} = \text{peso} \times \left(1 + \frac{\text{reps}}{30}\right)$$

### 1.3 Gestione Schede di Allenamento (Templates & Mesocicli)
- **Creazione Scheda**: L'utente può creare schede personalizzate (es. *Push / Pull / Legs* o *Upper / Lower*) definendo per ogni giorno gli esercizi, serie target, range di ripetizioni (es. 8-10) e RPE/carico indicativo.
- **Confronto Live "Pianificato vs Eseguito"**: Quando l'utente registra un allenamento da Garmin, il sistema confronta la sessione con la scheda attiva evidenziando:
  - Esercizi completati vs saltati.
  - Serie e carichi rispetto al target.
  - Variazione di tonnellaggio (+% rispetto alla settimana precedente).

### 1.4 Target di Volume Settimanale (Modello MEV / MAV / MRV)
Per ogni gruppo muscolare (*Petto, Dorso, Spalle, Gambe, Braccia, Core*), l'utente o il sistema imposta i range di serie settimanali:
- **MEV (Minimum Effective Volume)**: Serie minime per stimolare l'ipertrofia (es. 8-10 serie/settimana).
- **MAV (Maximum Adaptive Volume)**: Volume ottimale per la massima crescita (es. 12-18 serie/settimana).
- **MRV (Maximum Recoverable Volume)**: Soglia oltre la quale subentra l'overtraining (es. > 22 serie/settimana).

La dashboard mostrerà un indicatore a semaforo/progresso settimanale per ogni gruppo muscolare.

---

## 2. Modello Dati e Database (Liquibase)

### Tabella `exercise_personal_record`
```yaml
databaseChangeLog:
  - changeSet:
      id: 006-create-exercise-personal-record
      author: giuseppe
      changes:
        - createTable:
            tableName: exercise_personal_record
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: exercise_name
                  type: VARCHAR(100)
                  constraints:
                    nullable: false
              - column:
                  name: record_type
                  type: VARCHAR(30) # MAX_WEIGHT, MAX_1RM, MAX_VOLUME_SET, MAX_REPS
                  constraints:
                    nullable: false
              - column:
                  name: record_value
                  type: DOUBLE PRECISION
                  constraints:
                    nullable: false
              - column:
                  name: weight_kg
                  type: DOUBLE PRECISION
              - column:
                  name: reps
                  type: INTEGER
              - column:
                  name: activity_id
                  type: BIGINT
              - column:
                  name: achieved_at
                  type: TIMESTAMP
                  constraints:
                    nullable: false
```

### Tabelle `workout_template` e `workout_template_exercise`
```yaml
databaseChangeLog:
  - changeSet:
      id: 007-create-workout-template-tables
      author: giuseppe
      changes:
        - createTable:
            tableName: workout_template
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: VARCHAR(100)
                  constraints:
                    nullable: false
              - column:
                  name: description
                  type: VARCHAR(255)
              - column:
                  name: is_active
                  type: BOOLEAN
                  defaultValueBoolean: true
              - column:
                  name: created_at
                  type: TIMESTAMP
        - createTable:
            tableName: workout_template_exercise
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
              - column:
                  name: template_id
                  type: BIGINT
                  constraints:
                    nullable: false
                    foreignKeyName: fk_template_exercise
                    references: workout_template(id)
              - column:
                  name: day_of_week
                  type: INTEGER # 1 = Lunedì ... 7 = Domenica
              - column:
                  name: exercise_name
                  type: VARCHAR(100)
                  constraints:
                    nullable: false
              - column:
                  name: muscle_group
                  type: VARCHAR(50)
              - column:
                  name: target_sets
                  type: INTEGER
              - column:
                  name: min_reps
                  type: INTEGER
              - column:
                  name: max_reps
                  type: INTEGER
              - column:
                  name: order_index
                  type: INTEGER
```

---

## 3. Servizi Backend (`garmin-service`)

1. **`PersonalRecordService.java`**:
   - `void evaluateAndSaveRecords(List<StrengthWorkoutSet> sets, Long activityId, LocalDateTime activityDate)`: analizza le serie, calcola 1RM e confronta con i record esistenti su DB.
   - `List<PersonalRecordDTO> getRecordsByExercise(String exerciseName)`
   - `List<PersonalRecordDTO> getAllCurrentRecords()`
2. **`WorkoutTemplateService.java`**:
   - CRUD per schede di allenamento e relative associazioni di esercizi per giorno.
   - `TemplateComparisonDTO compareWorkoutWithTemplate(Long activityId, Long templateId)`: confronta la sessione reale con il target pianificato.
3. **`VolumeTargetService.java`**:
   - Calcola il bilancio settimanale per muscolo rispetto a MEV/MAV/MRV:
     - *Verde*: MAV raggiunto (ottimale)
     - *Giallo*: MEV raggiunto (mantenimento)
     - *Rosso*: Oltre MRV (rischio recupero insufficiente)

---

## 4. API Endpoints

- `GET /api/records`: Lista di tutti i record personali correnti suddivisi per esercizio.
- `GET /api/records/history?exercise=...`: Cronologia dell'evoluzione dei record per un esercizio.
- `GET /api/templates`: Lista dei template di allenamento.
- `POST /api/templates`: Creazione/modifica di un template.
- `GET /api/templates/active/compare?activityId=...`: Confronto tra sessione svolta e scheda attiva.
- `GET /api/volume-targets/weekly`: Riepilogo settimanale serie per gruppo muscolare vs target MEV/MRV.

---

## 5. Mockup & Componenti UI

1. **Tabella Esercizi con Icone PR**:
   - Se una serie ha registrato un nuovo record, compare una medaglia dorata animata accanto al carico o alle ripetizioni con tooltip: *"Nuovo Record Personale: 70kg (+5kg rispetto al 10 Agosto)"*.
2. **Sezione Trophy Room / Hall of Fame**:
   - Scheda dedicata con i massimali (1RM) storici dei 4-5 esercizi fondamentali (Panca, Squat, Stacco, Military, Trazioni).
3. **Grafico Radar del Volume Muscolare**:
   - Grafico a ragnatela che mostra il bilanciamento del volume tra Petto, Dorso, Spalle, Gambe, Braccia rispetto al target ideale.
