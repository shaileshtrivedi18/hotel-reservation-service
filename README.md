# hotel-reservation-service

Marvel Hospitality Management Corporation — Room Reservation Microservice

## Prerequisites

To build and run this Spring Boot service locally you need:

- **Java 17 JDK**
  - Verify with: `java -version`
- **Maven 3.8+**
  - Verify with: `mvn -version`
- **Kafka broker** (only if you want to run Kafka flows locally; not needed for tests)
  - Example: a local Dockerized Kafka at `localhost:9092`
  - Or adjust `spring.kafka.bootstrap-servers` in `application.yml` to your broker


## Tech Stack
- **Java 17**
- **Spring Boot 3.3.5**
- **Spring Data JPA** + H2 (in-memory, dev/test)
- **Spring Kafka** (consumer)
- **RestClient**
- **OpenAPI Generator** (client generated from YAML contract)
- **Lombok (@Data, @Builder)** for DTOs and entities

---

## Build & Run

```bash
# First time — generates code from OpenAPI YAML + compiles + runs tests
mvn clean install

# Run the app
mvn spring-boot:run
```

App starts on: http://localhost:8080

---

## OpenAPI Client Generation

The credit-card-payment-service client is **auto-generated** from:
```
src/main/resources/openapi/creditcardpayment_api.yaml
```

Generated code lands in `target/generated-sources/openapi/` under:
- `com.marvel.reservation.generated.client` — HTTP client
- `com.marvel.reservation.generated.model` — DTOs
- `com.marvel.reservation.generated.invoker` — ApiClient infrastructure

To regenerate after YAML changes:
```bash
mvn generate-sources
```

---

## API Endpoint

### POST /api/v1/reservations/confirm

**Request:**
```json
{
  "customerName": "Tony Stark",
  "roomNumber": "101",
  "startDate": "2026-08-01",
  "endDate": "2026-08-05",
  "roomSegment": "LARGE",
  "paymentMode": "CREDIT_CARD",
  "paymentReference": "CC-REF-12345"
}
```

**Sample curl (CASH payment)**

curl -X POST http://localhost:8080/api/v1/reservations/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "customerName": "Tony Stark",
    "roomNumber": "101",
    "startDate": "2026-08-01",
    "endDate": "2026-08-05",
    "roomSegment": "LARGE",
    "paymentMode": "CASH"
  }'


**Response:**
```json
{
  "reservationId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "CONFIRMED"
}
```

**Payment mode behaviour:**

| Mode          | Behaviour                                              | Status          |
|---------------|--------------------------------------------------------|-----------------|
| CASH          | Confirmed immediately                                  | CONFIRMED        |
| CREDIT_CARD   | Calls credit-card-payment-service; confirmed or 402    | CONFIRMED / 402  |
| BANK_TRANSFER | Saved as pending; confirmed via Kafka event            | PENDING_PAYMENT  |

**Validation rules:**
- Room cannot be reserved for more than 30 days → 400
- `paymentReference` required for CREDIT_CARD → 400
- `startDate` and `endDate` must be in the future → 400
- `endDate` must be after `startDate` → 400
- A Room once booked for a particular dates then it cannot be booked again for same or overlapping dates

**Room Segment values:** `SMALL`, `MEDIUM`, `LARGE`, `EXTRA_LARGE`

**Payment Mode values:** `CASH`, `CREDIT_CARD`, `BANK_TRANSFER`

---

## Kafka Consumer

**Topic:** `bank-transfer-payment-update`

**Event format:**
```json
{
  "paymentId": "PAY001",
  "debtorAccountNumber": "NL91ABNA0417164300",
  "amountReceived": 250.00,
  "transactionDescription": "1401541457 <reservationId>"
}
```

The `reservationId` is parsed from the second token of `transactionDescription`.

---

## Auto-Cancel Scheduler

Runs daily at **02:00 AM** (configurable via `reservation.auto-cancel.cron`).

Cancels all `BANK_TRANSFER` / `PENDING_PAYMENT` reservations whose `startDate` is within 2 days.

Only BANK_TRANSFER reservations in PENDING_PAYMENT are cancelled; CASH and already confirmed reservations are never touched.

---

## H2 Console (Only for dev. Disabled for Production)

URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:reservationdb`
- Username: `sa`
- Password: *(blank)*

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `credit-card-payment-service.base-url` | localhost:9090 | External payment service URL |
| `credit-card-payment-service.timeout-seconds` | 5 | HTTP timeout |
| `spring.kafka.bootstrap-servers` | localhost:9092 | Kafka broker |
| `reservation.auto-cancel.cron` | `0 0 2 * * *` | Auto-cancel schedule |
