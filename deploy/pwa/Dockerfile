FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000 \
    NODE_OPTIONS=--max-old-space-size=384 \
    TOKIO_WORKER_THREADS=2 \
    UV_THREADPOOL_SIZE=2 \
    PIPO_PWA_DATA_DIR=/data \
    PIPO_PWA_BRIDGE_BIN=/app/bin/pipo_pwa_bridge
COPY --chown=node:node app/ ./
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD node -e "require('http').get('http://127.0.0.1:3000/pwa',r=>{r.resume();process.exit(r.statusCode===200?0:1)}).on('error',()=>process.exit(1))"
CMD ["node", "server.js"]
