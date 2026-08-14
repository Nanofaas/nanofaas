FROM docker.io/library/node:20-alpine AS build
WORKDIR /src

COPY sdks/javascript ./sdks/javascript
COPY functions/javascript/word-stats /src/functions/javascript/word-stats

WORKDIR /src/sdks/javascript
RUN npm ci
RUN npm run build


WORKDIR /src/functions/javascript/word-stats
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev

FROM docker.io/library/node:20-alpine
WORKDIR /app

COPY --from=build /src/sdks/javascript /sdks/javascript
COPY --from=build /src/functions/javascript/word-stats/package.json ./package.json
COPY --from=build /src/functions/javascript/word-stats/node_modules ./node_modules
COPY --from=build /src/functions/javascript/word-stats/dist ./dist

EXPOSE 8080
USER node
CMD ["node", "dist/src/index.js"]
