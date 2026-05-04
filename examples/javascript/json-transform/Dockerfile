FROM node:20-alpine AS building
WORKDIR /src

COPY function-sdk-javascript ./function-sdk-javascript
WORKDIR /src/function-sdk-javascript
RUN npm ci
RUN npm run build

COPY examples/javascript/json-transform /src/examples/javascript/json-transform

WORKDIR /src/examples/javascript/json-transform
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev

FROM node:20-alpine
WORKDIR /app

COPY --from=building /src/function-sdk-javascript /function-sdk-javascript
COPY --from=building /src/examples/javascript/json-transform/package.json ./package.json
COPY --from=building /src/examples/javascript/json-transform/node_modules ./node_modules
COPY --from=building /src/examples/javascript/json-transform/dist ./dist

EXPOSE 8080
CMD ["node", "dist/src/index.js"]
