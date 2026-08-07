package com.codearena.api.repository;

import com.codearena.api.domain.UserTagStats;
import com.codearena.api.domain.UserTagStatsId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserTagStatsRepository extends JpaRepository<UserTagStats, UserTagStatsId> {

    @EntityGraph(attributePaths = "tag")
    List<UserTagStats> findByUserId(Long userId);

    long countByUserId(Long userId);
}
