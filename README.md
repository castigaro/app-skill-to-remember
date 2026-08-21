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

## Build

Standard-Gradle-Projekt (Kotlin, AGP 8.5, compileSdk 34), keine externen
Module.

```
git clone https://github.com/AppSonar/app-skill-to-remember
cd app-skill-to-remember
./gradlew assembleDebug
```

APK liegt danach unter `app/build/outputs/apk/debug/`.

CI baut per `.github/workflows/build.yml` und veröffentlicht die APKs samt
Versions-Manifest im GitHub-Release `latest`. Die App prüft aktuell nicht
selbst auf neue Versionen (kein Update-Checker eingebaut).
