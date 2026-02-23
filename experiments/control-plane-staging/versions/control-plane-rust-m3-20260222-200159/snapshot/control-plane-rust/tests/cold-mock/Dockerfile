FROM nginx:alpine

# Custom config: every response carries X-Cold-Start and X-Init-Duration-Ms headers.
RUN printf 'server {\n\
    listen 8080;\n\
    location / {\n\
        add_header X-Cold-Start "true" always;\n\
        add_header X-Init-Duration-Ms "50" always;\n\
        default_type application/json;\n\
        return 200 '"'"'{"message":"ok"}'"'"';\n\
    }\n\
}\n' > /etc/nginx/conf.d/default.conf

EXPOSE 8080
