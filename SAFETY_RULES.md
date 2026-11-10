# MeshLink Local Safety & Vulnerability Engine

## Purpose
Detect suspicious communication patterns locally, offline, and prevent uncontrolled propagation. This is a decision-support/policy engine, not a truth detector.

## Decisions
- LOW / policy passed → PASS
- MEDIUM / suspicious → HOLD for local review
- HIGH / severe rule combination → BLOCK locally

## Rules
- suspicious financial requests
- OTP/password/PIN/CVV requests
- suspicious HTTP/HTTPS or www links
- urgency / pressure language
- excessive punctuation
- very long messages
- forward-chain / viral language
- repeated identical messages from the same origin
- message flooding from the same origin
- malformed TTL/payload/timestamp
- dedicated SOS rate limit

## Emergency handling
SOS content is never blocked by the content classifier. A dedicated SOS rate policy may place excessive SOS traffic into HOLD to prevent flooding.

## Review flow
Receive → authenticate → safety policy → persist HOLD/BLOCK → Safety Review → Block / Keep Held / Pass & Release.

A PASS decision releases the reviewed packet for local delivery and/or forwarding.

## Limitation
The engine cannot prove that a real-world statement is true or false. It flags risk patterns such as phishing, spam, abuse, repetition and flood behavior.
