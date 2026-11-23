#!/bin/sh

create_db_and_user() {
    SERVICE_DB_NAME="$1" SERVICE_DB_USER="$2" SERVICE_DB_PASSWORD="$3" \
        psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv db SERVICE_DB_NAME
\getenv role SERVICE_DB_USER
\getenv password SERVICE_DB_PASSWORD
CREATE ROLE :"role" LOGIN PASSWORD :'password';
CREATE DATABASE :"db" OWNER :"role";
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
SQL
}

create_db_and_user "${AUTH_DB_NAME}" "${AUTH_DB_USER}" "${AUTH_DB_PASSWORD}" &&
create_db_and_user "${WALLET_DB_NAME}" "${WALLET_DB_USER}" "${WALLET_DB_PASSWORD}" &&
create_db_and_user "${NOTIFICATION_DB_NAME}" "${NOTIFICATION_DB_USER}" "${NOTIFICATION_DB_PASSWORD}"
