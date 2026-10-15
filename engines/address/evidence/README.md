# evidence

Evidence of a run goes to the pull request (text and images), not to git. Only this file is tracked.

What evidence contains:
- Counts: addresses read, written, FAILED, per `verifyStatus`; run time; calls to outside services.
- The match rate against the reference set and how it is computed.
- At most five example addresses, with unit numbers and building numbers masked.
- A screenshot of the web order list showing Korean addresses and positions on the map, with the
  customer fields hidden.

What it never contains: names, phone numbers, tracking numbers, tokens, full address lists.
