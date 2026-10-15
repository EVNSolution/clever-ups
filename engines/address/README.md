# address engine

Turns the English address of an order into a Korean address and a position, checks it against the
reference set, and writes the result into the database through the API.

## Fixed layout (do not add or rename entries at this level)

| Entry | Who writes it | What it is |
| --- | --- | --- |
| `README.md` | owner | This contract |
| `RUNBOOK.md` | engine developer | How to start everything needed on a laptop, step by step |
| `engine.toml` | engine developer | Name, version, start command, tunable parameters |
| `src/` | engine developer | The code. Any layout inside |
| `tests/` | engine developer | The engine's tests |
| `evidence/` | engine developer | Only a `README.md` is tracked; see it |

## What it may read and write (service token, `Authorization: Bearer ...`)

| Call | Use |
| --- | --- |
| `POST /api/addresses/refresh?from=&to=` | Queue the new addresses of some days as PENDING |
| `GET /api/addresses?status=PENDING&limit=500` | The queue |
| `GET /api/addresses/references?limit=500&after=` | The reference set (not confirmed answers) |
| `PUT /api/addresses/{key}` | The result for one address |

The token file path comes from the environment (`UPS_SERVICE_TOKEN_FILE`). Never put a token in an
argument, a log or a commit.

## What it fills

`addressKo`, `detailKo`, `koSource` (`TRANSLATOR`), `lat`, `lng`, `status` (`DONE` or `FAILED`), and
`verifyStatus` (`MATCH`, `MISMATCH`, `NO_REFERENCE`).

It does not fill zones, centers or drivers, and it does not write the reference set.

## Rules

- No customer name, phone number or tracking number in logs, evidence or commits. Counts only.
- No address list, result file or model weight in git (the root `.gitignore` blocks the usual
  extensions; do not work around it).
- Re-running must give the same stored result for the same input (idempotent). `FAILED` is not
  retried forever.
- Size and call limits of any outside service are written in `engine.toml` and in the runbook.
- A change to a call above is proposed in a comment on the issue first. The owner changes the API.
