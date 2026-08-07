package com.codearena.api.repository;

import com.codearena.api.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    List<Tag> findAllByNameIn(Collection<String> names);

    boolean existsByName(String name);

    /**
     * Loads every tag with its prerequisite edges attached, so the topological sort in the
     * recommendation engine can build the whole DAG from a single round trip.
     */
    @Query("""
            SELECT DISTINCT t FROM Tag t
            LEFT JOIN FETCH t.prerequisites
            """)
    List<Tag> findAllWithPrerequisites();
}
