FROM node:20-alpine AS building
WORKDIR /src

COPY sdks/javascript ./function-sdk-javascript
WORKDIR /src/function-sdk-javascript
RUN npm ci
RUN npm run build

COPY functions/javascript/word-stats /src/functions/javascript/word-stats

WORKDIR /src/functions/javascript/word-stats
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev

FROM node:20-alpine
WORKDIR /app

COPY --from=building /src/function-sdk-javascript /function-sdk-javascript
COPY --from=building /src/functions/javascript/word-stats/package.json ./package.json
COPY --from=building /src/functions/javascript/word-stats/node_modules ./node_modules
COPY --from=building /src/functions/javascript/word-stats/dist ./dist

EXPOSE 8080
CMD ["node", "dist/src/index.js"]
