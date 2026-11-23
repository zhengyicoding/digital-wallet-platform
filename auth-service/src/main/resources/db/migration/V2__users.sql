CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(20) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_]{3,20}$'),
    password_hash text NOT NULL,
    display_name varchar(50) NOT NULL CHECK (char_length(display_name) >= 1),
    administrator boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX users_one_administrator ON users (administrator) WHERE administrator;
