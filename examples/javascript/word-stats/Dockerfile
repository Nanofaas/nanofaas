FROM node:20-alpine AS build
WORKDIR /src

COPY function-sdk-javascript ./function-sdk-javascript
WORKDIR /src/function-sdk-javascript
RUN npm ci
RUN npm run build

COPY examples/javascript/word-stats /src/examples/javascript/word-stats

WORKDIR /src/examples/javascript/word-stats
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev

FROM node:20-alpine
WORKDIR /app

COPY --from=build /src/examples/javascript/word-stats/package.json ./package.json
COPY --from=build /src/examples/javascript/word-stats/node_modules ./node_modules
COPY --from=build /src/examples/javascript/word-stats/dist ./dist

EXPOSE 8080
CMD ["node", "dist/src/index.js"]
