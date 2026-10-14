# Alarm Actions

Alarm planner for Home Assistant that starts scripts before, at or after your alarms.

See [DOCS.md](DOCS.md) for installation and use.

## Development

Requires Node.js 22.

```sh
cd alarm_actions
npm install
npm run typecheck && npm run lint && npm test
npm run build
```

Workspaces:

- `shared/`: types, validation schemas and schedule calculation, used by server and panel.
- `server/`: Fastify API, SQLite storage, scheduler and Home Assistant websocket client.
- `web/`: React panel (built with Vite into `web/dist`, served by the server).

Run the server locally against a Home Assistant instance with a long-lived token:

```sh
DATA_DIR=./.data HA_WS_URL=ws://homeassistant.local:8123/api/websocket \
SUPERVISOR_TOKEN=<token> TRUSTED_PROXY=127.0.0.1 npm start
```

Requests need the header `X-Remote-User-Id` (normally set by the ingress proxy).
