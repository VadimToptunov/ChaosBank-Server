# ChaosBank Server

The backend for **ChaosBank**, the deliberately buggy neobank/broker built as a practice
range for mobile QA and SDET work
([iOS](https://github.com/VadimToptunov/ChaosBank-iOS),
[Android](https://github.com/VadimToptunov/ChaosBank-Android)).

The apps run offline on an embedded mock by default. Point them at this server instead,
and you also get real HTTP, an API to test, and defects that live on the server side.
The defects work like the apps' ones. The code is correct, each defect is a guarded deviation, and you switch
them on per session or per request.

> **Status: stage 5.** Sign-in, accounts, transfers, exchange, orders, idempotency, the
> Slow and Flaky networks, an OpenAPI contract the tests enforce, the apps talking to it
> (`CHAOSBANK_BACKEND` / `-ChaosBankBackend`), a live price feed over WebSocket, KYC on
> the server, and 17 server-side defects.

## Run it

```bash
docker compose up --build
open http://localhost:8080/docs      # Swagger UI
```

Or without Docker (JDK 21): `./gradlew :server:run`.

## Sign in

Any non-empty username and password pass, as in the apps; the OTP is the real gate.
There is no SMS: the code is always `424242`.

```bash
curl -X POST localhost:8080/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alex","password":"pw"}'                       # → {"challengeId": …}
curl -X POST localhost:8080/v1/auth/otp -H 'Content-Type: application/json' \
  -d '{"challengeId":"<id>","code":"424242"}'                    # → {"accessToken": …}
curl localhost:8080/v1/accounts -H 'Authorization: Bearer <accessToken>'
```

The rules match the apps' auth ladder. The code is valid for 20 s. Three wrong codes lock the challenge,
and a resend (allowed after 30 s) unlocks it. Access tokens last 15 min. A refresh token
works once, and logout ends both.

## API

Everything is in [`openapi.yaml`](server/src/main/resources/openapi.yaml), served at
`/openapi.yaml` and rendered at `/docs`. Money is a decimal string (`"4820.55"`) both
ways, dates are epoch seconds, errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
`application/problem+json` with a `type` such as `insufficientFunds` or `otpLocked`.

| Area | Endpoints |
| --- | --- |
| auth | `POST /v1/auth/login`, `/otp`, `/otp/resend`, `/refresh`, `/logout` |
| accounts | `GET /v1/accounts`, `/v1/accounts/{currency}`, `/v1/transactions?limit=&cursor=` |
| money | `POST /v1/transfers`, `/v1/deposits`, `/v1/exchanges`; `GET /v1/fx/rates` |
| markets | `GET /v1/assets`, `/v1/quotes`, `/v1/holdings`, `/v1/orders`; `POST /v1/orders`; WebSocket `/v1/prices/stream` |
| KYC | `GET /v1/kyc/status`, `POST /v1/kyc/submissions` |

**Money writes need an `Idempotency-Key`.** A repeat with the same key and body answers the
first success again (with `Idempotent-Replayed: true`) and moves no money. The same key
on a different body is refused with `idempotencyKeyReused`.

**The server prices exchanges:** (amount − 0.5% fee) × rate, half-even to the cent, with
the apps' fixed rates. Orders fill at once, a market order at the `referencePrice` the
client showed and a limit order at its limit, as in the apps' mock. Server-side
pricing arrives with the live price feed in stage 5.

**Live prices.** Each session runs the apps' simulated feed step for step: the same
seeded random walk, one tick per 700 ms, so the same seed shows the same prices as the
app's own feed. `GET /v1/quotes` returns the latest tick. The WebSocket
`/v1/prices/stream` takes the same session and token headers as REST and sends one
`{"seq", "quotes"}` frame per tick, the current prices first.

**KYC.** The client sends what its document scan and face check read, and the server
decides with the apps' rules, in their order: an expired document, then a name other
than the account holder's, then a face that doesn't match is rejected. A session starts
verified, as the apps do. Expiry is judged on the session's clock.

A clean session answers exactly what the apps' embedded mock does. `CleanParityTest`
checks that against golden files generated from the app's own `SeedData`
(`tools/golden_from_app.py`).

## Chaos

Every request runs in a **session**: its own data, its own defects, its own clock, so
parallel tests don't collide. Without a header you get the `default` session, which
runs clean.

```bash
curl -X POST localhost:8080/chaos/sessions -H 'Content-Type: application/json' \
  -d '{"defects":["balanceReadReturnsZero"],"network":"flaky","seed":7}'
# then send X-Chaos-Session: <id> with every call (sign in within the session)

curl localhost:8080/v1/transactions -H 'Authorization: Bearer …' \
  -H 'X-Chaos-Defects: transactionsDupOnFetch'          # one defect, one request
```

| Method and path | What it does |
| --- | --- |
| `POST /chaos/sessions` | `{profile?, defects?, seed?, network?}` → a new session |
| `GET` / `PUT` / `DELETE /chaos/sessions/{id}` | read, change the defects / network of, or drop a session |
| `POST /chaos/sessions/{id}/reset` | back to the seed data and the wall clock, defects kept |
| `POST /chaos/sessions/{id}/clock` | `{advanceSeconds}`: expire an OTP or a token without waiting |
| `GET /chaos/catalog` | the server's defects and profiles |

**Networks.** `normal`. `slow`: +3 s per call, as in the apps' dev menu. `flaky`: half of
the money writes fail with 504 `timeout` before anything is applied. The failures follow
the session's `seed`, using the same draws as the apps, so a failing run reproduces.

`CHAOS_DEFAULT_PROFILE` / `CHAOS_DEFAULT_DEFECTS` switch defects on for the default session,
for demos and take-home assignments. `JWT_SECRET` keeps tokens valid across restarts.

## Server defects

Seventeen, each one guarded deviation with a test pair: green on a clean session, red under
exactly that defect. `GET /chaos/catalog` lists them with their requirement and the task
a tester is given. Ids match the apps' where it's the same defect, so an app running
against the server forwards it and it behaves as on the embedded mock.

| Defect | Category | What breaks |
| --- | --- | --- |
| `balanceReadReturnsZero` | State | balance reads answer 0 |
| `transactionsDupOnFetch` | State | every history row comes back twice |
| `staleHoldingsAfterOrder` | State | holdings ignore a filled order |
| `lostUpdateNoLock` | State | concurrent transfers overwrite each other's debit |
| `paginationCursorSkips` | State | a row is lost at every page edge |
| `retryDuplicate` | Network | a repeated Idempotency-Key pays again |
| `timeoutAsSuccess` | Network | a transfer answers 504 but was applied |
| `orderFilledTwiceOnRetry` | Network | an order answers 504, and a retry with the same key fills it again |
| `serverRoundsDown` | Money | a debit drops its cents |
| `negativeAmountCredits` | Money | a negative transfer adds money |
| `fxRateStaleOnServer` | Money | an exchange is credited at a rate other than the one it reports |
| `contractFieldRenamed` | Contract | one endpoint calls `balance` `amount` |
| `wsPricesOutOfOrder` | Realtime | the price stream sends ticks in swapped pairs (seq 2, 1, 4, 3…) |
| `kycAcceptsExpiredDocument` | Validation | an expired document passes KYC |
| `kycNameMismatchAccepted` | Validation | someone else's document passes KYC |
| `kycSelfieMismatchAccepted` | Validation | a face that doesn't match passes KYC |
| `kycStuckPending` | State | a passing check stays pending |

Profiles: `money`, `state`, `network`, `contract`, `realtime`, `kyc`, `all`.

Some are invisible in a clean app's UI on purpose. For example, a 504 with the money
gone looks like an ordinary error until you check the balance. Others only show through
the API (`contractFieldRenamed`), a proxy, or concurrency (`lostUpdateNoLock`). Finding
which side is lying is the exercise.

## Tests

`./gradlew test`. Every response a test receives is checked against `openapi.yaml`: an
undocumented status, field or content type fails the test. `ContractCoverageTest` also
checks that every route is documented and every documented operation exists.

## Layout

```
chaos/    the chaos engine: catalog, profiles, sessions, clock, seeded RNG (no HTTP; future ChaosKit)
server/   Ktor app: routes, the bank domain, auth, seed data, openapi.yaml
tools/    golden_from_app.py: regenerates the parity fixtures from the app's seed
```

## License

[MIT](LICENSE)
