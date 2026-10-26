# ChaosBank Server

The backend for **ChaosBank**, the deliberately buggy neobank/broker built as a practice
range for mobile QA and SDET work
([iOS](https://github.com/VadimToptunov/ChaosBank-iOS),
[Android](https://github.com/VadimToptunov/ChaosBank-Android)).

The apps run offline on an embedded mock by default. Point them at this server instead,
and you also get real HTTP, an API to test, and defects that live on the server side.
The defects work like the apps' ones. The code is correct, each defect is a guarded deviation, and you switch
them on per session or per request.

> **Status: stage 1 of 5.** Accounts, history, chaos sessions and Docker. Transfers,
> exchange, orders, auth and OpenAPI come in stage 2.

## Run it

```bash
docker compose up --build
curl localhost:8080/v1/accounts
```

Or without Docker (JDK 21):

```bash
./gradlew :server:run
```

## API (stage 1)

Money is a decimal string (`"4820.55"`), dates are epoch seconds, errors are
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.

| Method and path | What it does |
| --- | --- |
| `GET /v1/accounts` | the three accounts, EUR, USD, GBP |
| `GET /v1/accounts/{currency}` | one account; unknown currency → 404 `unknownAccount` |
| `GET /v1/transactions?limit=&cursor=` | history, newest first; `nextCursor` is `null` on the last page |
| `GET /health` | liveness |

A clean session answers exactly what the apps' embedded mock does. That's the
`CleanParityTest`, against golden files generated from the app's `SeedData`
(`tools/golden_from_app.py`).

## Chaos

Every request runs in a **session**: its own data and its own defects, so parallel tests
don't collide. Without a header you get the `default` session, which runs clean.

```bash
# A session with a defect on, then use it
curl -X POST localhost:8080/chaos/sessions -H 'Content-Type: application/json' \
  -d '{"defects":["balanceReadReturnsZero"],"network":"slow"}'
curl localhost:8080/v1/accounts -H 'X-Chaos-Session: <id>'

# Or one defect for one stateless request
curl localhost:8080/v1/transactions -H 'X-Chaos-Defects: transactionsDupOnFetch'
```

| Method and path | What it does |
| --- | --- |
| `POST /chaos/sessions` | `{profile?, defects?, seed?, network?}` → a new session |
| `GET` / `PUT` / `DELETE /chaos/sessions/{id}` | read, change the defects / network of, or drop a session |
| `POST /chaos/sessions/{id}/reset` | back to the seed data, defects kept |
| `GET /chaos/catalog` | the server's defects and profiles |

`network` is `normal`, `slow` (+3 s per call, as in the apps' dev menu) or `flaky`.
`CHAOS_DEFAULT_PROFILE` / `CHAOS_DEFAULT_DEFECTS` switch defects on for the default
session, which suits demos and take-home assignments:

```bash
CHAOS_DEFAULT_DEFECTS=balanceReadReturnsZero docker compose up
```

## Layout

```
chaos/    the chaos engine: catalog, profiles, sessions (no HTTP; future ChaosKit)
server/   Ktor app: routes, the bank domain, its in-memory state, seed data
tools/    golden_from_app.py: regenerates the parity fixtures from the app's seed
```

## License

[MIT](LICENSE)
