# TRL Patrol Link 1.1.53 Tablet — changes

- Native landscape dashboard on tablets (smallest width at least 600 dp): persistent 35% guard column and 65% interactive geographic OpenStreetMap view. Phone and Android Auto interfaces remain in place.
- Real relay GPS, local GPS and centrally synced checkpoint markers; separate colours, original timestamps, offline/stale retention and configurable 15/30/60-second fleet display updates.
- Supervisor fleet restriction retained. Full tracking requires Master / Supervisor role; ordinary patrol devices retain access and credential boundaries.
- Compact automatic proximity panel: two distinct precise fixes, accuracy-aware entry, drift hysteresis, overlap handling and dismissal until exit. Options include 5/10/15/20/25/50 m or custom 1–500 m; centrally configured individual distances remain available.
- Physical QR scanning remains separate from GPS. Approaching never records completion. Authoritative matching source scans after arrival can suppress the prompt.
- Existing checkpoint manager's 20 m editing clamp removed with finite numeric validation.
- One settings cog on the tablet dashboard; existing guard, assignment, GPS, checkpoint, feed, voice, sign-out and diagnostic controls retained through it.
- Existing package, signing identity, preferences, Keystore format and fleet/Master contracts retained. Version code 163.
- GPS upload throttling respects the existing 15/30-second reporting setting while retaining fresh local fixes for small-radius prompts. Mock fixes are not published as patrol GPS.
- New runtime library: osmdroid 6.1.20. OpenStreetMap HTTPS street tiles are cached in app storage and visibly attributed.
