FROM docker.io/library/node:20-alpine AS build
WORKDIR /src

COPY sdks/javascript ./sdks/javascript
COPY functions/javascript/roman-numeral /src/functions/javascript/roman-numeral

WORKDIR /src/sdks/javascript
RUN npm ci
RUN npm run build


WORKDIR /src/functions/javascript/roman-numeral
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev
# The function's prebuild reinstalls the SDK with its dev dependencies: prune them last.
RUN npm --prefix /src/sdks/javascript prune --omit=dev

# Only the node binary: npm, yarn and corepack stay in the build stage.
FROM docker.io/library/alpine:3.22
RUN apk add --no-cache libstdc++ && \
    addgroup -g 1000 node && adduser -u 1000 -G node -s /bin/sh -D node
COPY --from=build /usr/local/bin/node /usr/local/bin/node
WORKDIR /app

# The SDK's runtime files only: its sources, tests and development dependencies stay behind.
COPY --from=build /src/sdks/javascript/package.json /sdks/javascript/package.json
COPY --from=build /src/sdks/javascript/dist /sdks/javascript/dist
COPY --from=build /src/sdks/javascript/node_modules /sdks/javascript/node_modules
COPY --from=build /src/functions/javascript/roman-numeral/package.json ./package.json
COPY --from=build /src/functions/javascript/roman-numeral/node_modules ./node_modules
COPY --from=build /src/functions/javascript/roman-numeral/dist ./dist

EXPOSE 8080
USER node
CMD ["node", "dist/src/index.js"]
