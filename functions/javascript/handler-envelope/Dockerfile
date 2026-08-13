FROM docker.io/library/node:20-alpine AS build
WORKDIR /src
COPY sdks/javascript ./sdks/javascript
COPY functions/javascript/handler-envelope /src/functions/javascript/handler-envelope
WORKDIR /src/sdks/javascript
RUN npm ci && npm run build
WORKDIR /src/functions/javascript/handler-envelope
RUN npm install && npm run build && npm prune --omit=dev
FROM docker.io/library/node:20-alpine
WORKDIR /app
COPY --from=build /src/sdks/javascript /sdks/javascript
COPY --from=build /src/functions/javascript/handler-envelope/package.json ./package.json
COPY --from=build /src/functions/javascript/handler-envelope/node_modules ./node_modules
COPY --from=build /src/functions/javascript/handler-envelope/dist ./dist
EXPOSE 8080
CMD ["node", "dist/src/index.js"]
