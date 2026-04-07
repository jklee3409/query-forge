package io.queryforge.backend.admin.persistence.repository;

import io.queryforge.backend.admin.persistence.entity.CorpusRunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CorpusRunStepJpaRepository extends JpaRepository<CorpusRunStepEntity, UUID> {
}
