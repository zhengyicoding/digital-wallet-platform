FROM nginx:1.27-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /var/cache/nginx/client_temp \
    && chown -R app:app /var/cache/nginx
COPY nginx.conf /etc/nginx/nginx.conf
COPY public/ /usr/share/nginx/html/
USER app
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
