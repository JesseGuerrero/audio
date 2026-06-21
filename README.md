# ktor_voice

Self-contained Ktor sync server for overhead "voice"/speaker icons. Run it
alongside the world server; game clients poll it to stay in sync on which
players show the icon and which animation frame is current.

## Run (dev)

```bash
./gradlew run
```

## Build a deployable fat jar

```bash
./gradlew shadowJar
# -> build/libs/ktor-voice.jar
java -jar build/libs/ktor-voice.jar
```

## Config (env vars)

| var            | default | meaning                    |
|----------------|---------|----------------------------|
| `VOICE_PORT`   | 8080    | listen port                |
| `VOICE_CYCLE_MS` | 3000  | ms per sprite frame        |
| `VOICE_SPRITES`  | 6     | number of sprites in cycle |

## API

- `POST /icon/{name}` — register a player display-name as an icon owner
- `DELETE /icon/{name}` — remove a player
- `GET /state` — plain text; line 1 = cycle index, following lines = owner names
- `GET /health` — `ok`

State is in-memory only (cleared on restart).
