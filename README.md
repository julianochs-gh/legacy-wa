# LegacyWA

WhatsApp als **verknüpftes Gerät** auf Android 4.0+ (ARMv7). Die App bringt eine kleine Engine
(Go, [whatsmeow](https://github.com/tulir/whatsmeow)) mit, die direkt auf dem Handy läuft – kein PC-Server nötig.

**Download:** Releases → `latest` → `LegacyWA.apk` (wird bei jedem Push automatisch von GitHub Actions gebaut).

Funktionen (v1): Koppeln per QR-Code oder Telefonnummer, Chatliste, Text lesen/senden, Lesebestätigungen,
Benachrichtigungen. Bilder/Sprachnachrichten werden nur als Platzhalter angezeigt.

Hinweise:
- Inoffizieller Client – Nutzung auf eigenes Risiko (Nutzungsbedingungen von WhatsApp).
- Go setzt offiziell Linux-Kernel ≥ 3.2 voraus. Auf älteren Kerneln kann die Engine abstürzen
  (Menü → „Log anzeigen“ zeigt Details).
