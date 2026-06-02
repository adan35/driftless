package io.driftless.ledger.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the immutable {@code account} table. */
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {}
