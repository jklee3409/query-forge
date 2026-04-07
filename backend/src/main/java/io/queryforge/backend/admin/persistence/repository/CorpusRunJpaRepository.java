package io.queryforge.backend.admin.persistence.repository;

import io.queryforge.backend.admin.persistence.entity.CorpusRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface CorpusRunJpaRepository extends JpaRepository<CorpusRunEntity, UUID> {

    Optional<CorpusRunEntity> findFirstByRunStatusInOrderByCreatedAtDesc(Collection<String> runStatuses);
}
