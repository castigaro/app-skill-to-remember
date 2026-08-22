# SkillToRemember

KI-Chat für Android mit eigenem API-Key. Eigene Skills lassen sich
installieren und stehen dem Chat zur Verfügung. Bringt einen
Gedächtnis-Skill mit, der über Sitzungen hinweg lernt und in einem privaten
Git-Repo gespeichert wird.

## Chat

Anthropic (Claude) oder OpenAI (GPT) mit eigenem API-Key, direkt per HTTP
angebunden (kein SDK). Modell frei wählbar, Kostenzähler pro Anbieter.

## Skills

Jeder Skill hat Name, Beschreibung und eine Anleitung. Das Modell sieht bei
jeder Anfrage nur Name und Beschreibung der aktivierten Skills — passt
einer zur Anfrage, lädt es sich dessen volle Anleitung selbst per Tool-Call
nach (`load_skill`). Skills lassen sich in der App anlegen oder importieren:
als einzelne `SKILL.md`-Datei oder aus einer ZIP-Datei (z. B. GitHubs
"Download ZIP"), jeweils im Format des Agent-Skills-Standards
(agentskills.io).

## Gedächtnis (humanoid-behavior)

Fest eingebauter Skill. Bei jeder Antwort wird automatisch ein Digest des
Gedächtnisses vor den System-Prompt gesetzt; das Modell bedient das
Gedächtnis selbst über drei Tools — `remember`, `recall`, `forget`. Der
Speicher liegt lokal auf dem Gerät und wird bei jedem Schreibzugriff sofort
in ein privates GitHub-Repo committet und gepusht (über GitHubs
Git-Data-API, kein eingebetteter Git-Client).

Format und Logik (Layer, Salience-Schwelle, Verfall, Konsolidierung) sind
funktional identisch zum `humanoid-behavior`-Skill in
[`castigaro/my-ai-skills`](https://github.com/castigaro/my-ai-skills) —
derselbe Speicherort ist zwischen Desktop (dessen Python-Engine) und dieser
App austauschbar.

**Einrichten:** Einstellungen → Gedächtnis → leeres privates GitHub-Repo
eintragen (Benutzername, Repo-Name, Branch, Personal Access Token mit
`Contents: Read and write` auf genau dieses Repo) → Verbinden.

## Smartwatch (Wear OS)

Die App gibt es auch als reinen Sprach-Client für Wear-OS-Uhren (ab Wear
OS 3, z. B. Galaxy Watch 4): Aufs Mikrofon tippen, sprechen, die Antwort
wird vorgelesen, danach hört die Uhr automatisch wieder zu. Antworten
kommen bewusst knapp (Details auf Nachfrage). Die Uhr nutzt dieselben
Skills und dasselbe Gedächtnis-Repo wie das Handy.

**Einstellungen ohne Tipperei:** In der Handy-App unter Einstellungen →
„Einstellungen an die Uhr senden" wandern API-Key, Gedächtnis-Zugang und
Stimm-Einstellungen per Bluetooth an die Uhr (verschlüsselt, nur an die
SkillToRemember-App mit gleicher Signatur).

**Installation per adb** (einmalig, die Uhr kann keine APKs herunterladen):

1. *Auf der Uhr:* Einstellungen → Info zur Uhr → Softwareinformationen →
   fünfmal auf „Softwareversion" tippen (schaltet Entwickleroptionen frei).
   Dann Einstellungen → Entwickleroptionen → „ADB-Debugging" und
   „Debugging über WLAN" aktivieren. Die angezeigte IP-Adresse merken;
   Uhr und PC müssen im selben WLAN sein.
2. *Am PC:* Googles „SDK Platform Tools" herunterladen
   (https://developer.android.com/tools/releases/platform-tools) und
   entpacken — darin liegt `adb`.
3. `skilltoremember-wear.apk` aus dem GitHub-Release herunterladen
   (wichtig: die Release-Variante, nur sie trägt dieselbe Signatur wie
   die Handy-App — sonst verweigert die Uhr die Einstellungs-Übernahme).
4. Im Terminal/der Eingabeaufforderung im Platform-Tools-Ordner:
   `./adb connect <IP-der-Uhr>:5555` (Anfrage auf der Uhr bestätigen),
   dann `./adb install -r skilltoremember-wear.apk`,
   abschließend `./adb disconnect`.
5. Debugging auf der Uhr wieder ausschalten (spart Akku), App öffnen,
   am Handy „Einstellungen an die Uhr senden" — fertig.

## Build

Standard-Gradle-Projekt (Kotlin, AGP 8.5, compileSdk 34) mit drei Modulen:
`:app` (Handy), `:wear` (Uhr) und `:core` (gemeinsame Logik — KI-Anbindung,
Speicher, Gedächtnis-Engine).

```
git clone https://github.com/AppSonar/app-skill-to-remember
cd app-skill-to-remember
./gradlew assembleDebug
```

APK liegt danach unter `app/build/outputs/apk/debug/`.

CI baut per `.github/workflows/build.yml` und veröffentlicht die APKs samt
Versions-Manifest im GitHub-Release `latest`. Die Handy-App prüft beim
Start auf neue Versionen und bietet in den Einstellungen eine manuelle
Update-Prüfung samt Installation an; die Uhr-App wird per adb aktualisiert.

## Lizenz

GNU Affero General Public License v3.0 (AGPL-3.0) — siehe [LICENSE](LICENSE).
Copyright © 2026 Torsten Klein (AppSonar, appsonar.de).

Frei nutzen, verändern und weitergeben. Wer eine veränderte Fassung
verbreitet — auch als Netzwerkdienst — muss den vollständigen Quellcode
unter derselben Lizenz offenlegen.
