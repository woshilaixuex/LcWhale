# Taste / Preferences
See [taste-/-preferences/taste.md](taste-/-preferences/taste.md)

- Wants to understand the internal implementation details of frameworks/libraries (e.g., asked how `keepCallbackAlive=true` works inside Kuikly's bridge, followed up on whether `toNative` communication is fundamentally callback-passing, and again on whether the same mechanism holds on iOS/OHOS), not just how to use the API — values explanations grounded in actual source code, not speculation. Asks probing follow-up questions to verify the mechanism and its cross-platform generality until the mental model is correct. Confidence: 0.9
- Verifies proposed implementation/design approaches with the assistant before writing code (e.g., asked "那我ws的callback要怎么写，只要透传ws的数据就行吗，到时候ws一有数据就callback?" to confirm the callback design), wanting the mental model validated up front rather than discovering mistakes after coding. Confidence: 0.6
