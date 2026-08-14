FROM docker.io/library/node:20-alpine AS build
WORKDIR /src
COPY sdks/javascript ./sdks/javascript
COPY functions/javascript/qr-code /src/functions/javascript/qr-code
WORKDIR /src/sdks/javascript
RUN npm ci && npm run build
WORKDIR /src/functions/javascript/qr-code
RUN npm ci && npm run build && npm prune --omit=dev

FROM docker.io/library/node:20-alpine
WORKDIR /app
COPY --from=build /src/sdks/javascript /sdks/javascript
COPY --from=build /src/functions/javascript/qr-code/package.json ./package.json
COPY --from=build /src/functions/javascript/qr-code/node_modules ./node_modules
COPY --from=build /src/functions/javascript/qr-code/dist ./dist
EXPOSE 8080
USER node
CMD ["node", "dist/src/index.js"]
