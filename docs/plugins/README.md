# Declarative plugins

A plugin is one JSON file. Import it in **Settings → Manage plugins**; it stays
disabled until you enable it. Enabled plugins are exposed to the model as
`plugin_<id>` tools next to `device_action`.

## Format

```json
{
  "id": "battery_webhook",
  "name": "Battery webhook",
  "description": "Read the battery status and POST it to a URL you provide.",
  "parameters": {
    "type": "object",
    "properties": {
      "url": { "type": "string" }
    },
    "required": ["url"]
  },
  "steps": [
    { "type": "shell", "command": "dumpsys battery" },
    { "type": "http", "method": "POST", "url": "{{url}}", "body": "{{result}}" }
  ]
}
```

- `id`: `[a-z0-9_]{1,32}`; the tool name is `plugin_<id>`
- `parameters`: JSON Schema sent to the model
- `steps`: 1–20 whitelisted steps, run in order

## Steps

| type | fields |
|---|---|
| `shell` | `command` — run through `su` (20s timeout) |
| `http` | `method` (`GET`/`POST`), `url`, optional `headers`, `body` |
| `action` | `action` = `tap` (`x`,`y`) / `tap_text` (`text`) / `text` (`text`) / `wait` (`ms`) / `key` (`text`) |

Templates: `{{param}}` is replaced with a tool argument, `{{result}}` with the
previous step's output. HTTP results and shell output are capped before they are
sent back to the model.

## Safety

- Imported plugins are **disabled by default**.
- Enabling a plugin with `shell` or `http` steps asks for confirmation.
- `shell` runs as root; only enable plugins you have reviewed.
- Plugin calls are recorded in the conversation and the Stop button still works.
