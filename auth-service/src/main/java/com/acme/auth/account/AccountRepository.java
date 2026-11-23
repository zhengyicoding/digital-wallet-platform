package com.acme.auth.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

interface AccountRepository extends Repository<Account, UUID> {

    Optional<Account> findByUsername(String username);

    Optional<Account> findById(UUID id);

    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO users (id, username, password_hash, display_name, administrator)
            VALUES (:id, :username, :passwordHash, :displayName, :administrator)
            ON CONFLICT (username) DO NOTHING
            """, nativeQuery = true)
    int insert(UUID id, String username, String passwordHash, String displayName, boolean administrator);
}
