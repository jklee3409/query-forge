package io.queryforge.backend.admin.persistence.repository;

import io.queryforge.backend.admin.persistence.entity.CorpusSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorpusSourceJpaRepository extends JpaRepository<CorpusSourceEntity, String> {
}
