# engines

An engine is a part that changes often and runs on its own, outside the API. It talks to the API
through HTTP and a service token, and through nothing else.

- The API never imports an engine and an engine never imports the API's code.
- The layout of this directory is fixed by the owner. Inside `src/` and `tests/` an engine is free.
- Adding an engine, or any entry outside the table below, is a change to this file and to
  `tests/test_engine_layout.py`, reviewed by the owner.

| Engine | Directory | What it fills |
| --- | --- | --- |
| address | `engines/address/` | Korean address, detail, position, verification result |
