# backend

Java 21 / Spring Boot. The API the Enrola platform would call: start a conversation for a
lead, hand it an inbound SMS, get the agent's turn back. See the [root README](../README.md)
for what the whole thing is and why.

```
POST /api/conversations            {leadId}  -> conversation with the opener
GET  /api/conversations/{id}
POST /api/conversations/{id}/messages  {body} -> conversation with the reply appended
POST /api/conversations/{id}/reset
GET  /api/leads
```

## Layout

| package        | what lives there                                                        |
|----------------|-------------------------------------------------------------------------|
| `web`          | controllers, DTOs, CORS, error mapping                                   |
| `engine`       | `AgentService` (one turn), `PromptBuilder`, `Guardrails`, OpenAI client   |
| `conversation` | `Conversation`/`Message`/`Booking` + JDBC repositories                   |
| `lead`         | seeded lead rows                                                         |
| `customer`     | `CustomerRegistry` — scans `customers/<id>/`, reloads prompts on mtime    |
| `calendly`     | `StubCalendlyClient` — deterministic slots off the injected `Clock`       |

Schema and seed data: `src/main/resources/{schema.sql,data.sql}`, applied on every boot,
create-if-absent so restarts keep their state.

## Run and test

```bash
docker compose up            # from the repo root; this is the normal path
./mvnw verify                # 70 tests, no API key, needs Docker for Testcontainers
./mvnw verify -Plive         # 76: adds live scenarios, needs OPENAI_API_KEY, costs money
./mvnw spring-boot:run       # bare metal: needs your own Postgres on localhost:5432
                             # (enrola/enrola/enrola) — compose deliberately doesn't publish 5432
```

Config is `src/main/resources/application.yml`; `DB_HOST`, `CUSTOMERS_DIR` and
`OPENAI_API_KEY` are the env vars compose sets.
