FROM node:20-slim
WORKDIR /app
COPY src /app/src
COPY package.json /app/package.json
EXPOSE 8090
ENV PORT=8090
HEALTHCHECK --interval=10s --timeout=3s --retries=5 --start-period=5s CMD node -e "require('http').get('http://127.0.0.1:8090/healthz',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"
CMD ["node", "src/server-main.js"]
