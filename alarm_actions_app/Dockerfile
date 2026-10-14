# Alarm Actions App add-on image: a tiny Node server with the signed APK inside.
# Built by the release workflow, which first builds and signs the APK (android/) and copies it
# here as alarm_actions.apk. Without an APK the image still builds and /info answers 503.
ARG NODE_VERSION=22
FROM node:${NODE_VERSION}-alpine

ARG APP_VERSION=0.0.0
ENV NODE_ENV=production \
    APP_VERSION=${APP_VERSION} \
    APK_PATH=/app/alarm_actions.apk
WORKDIR /app

# The glob keeps the build working when no APK was copied in (local or CI dry runs).
COPY server.mjs alarm_actions.ap[k] /app/

USER node
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:8000/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]
CMD ["node", "server.mjs"]
