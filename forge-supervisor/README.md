# Ronin Forge Supervisor 0.1.0
Local Windows supervisor for Vanta-style build/diagnose/repair loops.
Dashboard/API: http://127.0.0.1:8765
Imports Vanta/source ZIPs, uses the existing GitHub Android worker, reads logs/artifacts, requests targeted repairs from an OpenAI-compatible coding model, protects tests, records a repair ledger, and repeats until success or a circuit-breaker condition.