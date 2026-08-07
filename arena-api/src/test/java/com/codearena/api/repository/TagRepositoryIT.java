package com.codearena.api.repository;

import com.codearena.api.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TagRepository and the prerequisite DAG")
class TagRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TagRepository tagRepository;

    @Test
    @DisplayName("looks tags up by name")
    void findByName() {
        assertThat(tagRepository.findByName("shortest-path")).isPresent();
        assertThat(tagRepository.findByName("not-a-real-tag")).isEmpty();
        assertThat(tagRepository.existsByName("dp")).isTrue();
    }

    @Test
    @DisplayName("bulk-loads tags by name")
    void findAllByNameIn() {
        List<Tag> tags = tagRepository.findAllByNameIn(List.of("dp", "graph", "bfs", "nonsense"));

        assertThat(tags).extracting(Tag::getName).containsExactlyInAnyOrder("dp", "graph", "bfs");
    }

    @Test
    @DisplayName("prerequisite edges load with the tag")
    void prerequisitesAreLoaded() {
        Tag shortestPath = tagRepository.findAllWithPrerequisites().stream()
                .filter(t -> t.getName().equals("shortest-path"))
                .findFirst()
                .orElseThrow();

        assertThat(shortestPath.getPrerequisites()).extracting(Tag::getName)
                .containsExactlyInAnyOrder("bfs", "heap");
    }

    @Test
    @DisplayName("the seeded prerequisite graph is a DAG (Kahn's algorithm consumes every node)")
    void prerequisiteGraphIsAcyclic() {
        List<Tag> tags = tagRepository.findAllWithPrerequisites();

        // Edge direction for the sort: prerequisite -> dependent.
        Map<Long, List<Long>> dependents = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        tags.forEach(t -> inDegree.putIfAbsent(t.getId(), 0));
        for (Tag tag : tags) {
            for (Tag prerequisite : tag.getPrerequisites()) {
                dependents.computeIfAbsent(prerequisite.getId(), k -> new ArrayList<>()).add(tag.getId());
                inDegree.merge(tag.getId(), 1, Integer::sum);
            }
        }

        Deque<Long> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        List<Long> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Long current = ready.poll();
            order.add(current);
            for (Long dependent : dependents.getOrDefault(current, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        // If any node is left over, it sits on a cycle.
        assertThat(order)
                .as("topological order must cover every tag; a shortfall means the DAG has a cycle")
                .hasSize(tags.size());
    }

    @Test
    @DisplayName("the DAG has exactly the two intended roots")
    void rootsAreImplementationAndMath() {
        List<String> roots = tagRepository.findAllWithPrerequisites().stream()
                .filter(t -> t.getPrerequisites().isEmpty())
                .map(Tag::getName)
                .toList();

        assertThat(roots).containsExactlyInAnyOrder("implementation", "math");
    }
}
